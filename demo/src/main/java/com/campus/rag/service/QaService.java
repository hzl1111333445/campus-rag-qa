package com.campus.rag.service;

import com.campus.rag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问答服务：检索 + 让大模型基于资料生成回答 + 校验引用编号。
 *
 * <p>它是 RAG 的"组装线"：
 * <pre>
 * 检索 3 个块 → 拼提示词 → 调 DeepSeek → 校验回答里的 [1][2] → 返回
 * </pre>
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /** 匹配回答里的引用编号，比如 [1]、[2] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final LlmProperties llmProperties;
    private final QueryRewriter queryRewriter;
    private final ObjectMapper objectMapper;

    /**
     * 追问补全的触发阈值：按原句检索的最高分低于它，才认为"这句话自己检索不明白"，
     * 才值得多花一次补全 + 一次检索。正常问题 topScore 在 0.5 以上，
     * 省略式追问往往掉到 0.1 以下，0.30 留了足够余量。
     */
    private final double followupTriggerScore;

    public QaService(RetrievalService retrievalService,
                     PromptBuilder promptBuilder,
                     ChatClient chatClient,
                     LlmProperties llmProperties,
                     QueryRewriter queryRewriter,
                     @Value("${campus.context.followup-trigger-score:0.30}") double followupTriggerScore,
                     ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.llmProperties = llmProperties;
        this.queryRewriter = queryRewriter;
        this.followupTriggerScore = followupTriggerScore;
        this.objectMapper = objectMapper;
    }

    /**
     * 一条引用：告诉前端"第 n 条引用对应哪一块"。
     *
     * @param index         编号（回答里写的 [1] 就是这个 1）
     * @param chunkId       块 id
     * @param documentTitle 出自哪个文档
     * @param chunkIndex    第几块
     * @param score         检索时的相似度分数
     * @param content       这一块的原文（前端引用卡片要显示，用户才能自己核对"答案对不对"）
     */
    public record Reference(int index, long chunkId, String documentTitle, int chunkIndex,
                            double score, String content) {
    }

    /**
     * 一次问答的完整结果。
     *
     * @param answer            模型生成的回答
     * @param model             用的哪个模型
     * @param references        引用了哪几个块（前端用来显示"出处"卡片）
     * @param retrievedCount    检索回来几个块
     * @param citationOk        回答里的编号是否全部合法（没有编造）；true 不代表一定有引用
     * @param invalidCitations  编造的编号（比如资料只有 3 条，它却写了 [5]）
     * @param grounded          回答里的硬事实是否都能在被引用的原文里找到（接地校验，见 docs/10）
     * @param factCount         一共抽出几条可核对的硬事实（0 表示这段回答里没有数字/时间/处分名，无从核对）
     * @param missingFacts      在原文里找不到的事实（"0.20元"这种被改错的数字会出现在这里）
     * @param topScore          最高检索分（精排分或混合分，看有没有开精排）
     * @param gated             是否因为检索分太低而"在检索层就拒答"（这种情况没花大模型的钱）
     */
    public record QaAnswer(String question,
                           String answer,
                           String model,
                           List<Reference> references,
                           int retrievedCount,
                           boolean citationOk,
                           List<Integer> invalidCitations,
                           boolean grounded,
                           int factCount,
                           List<String> missingFacts,
                           double topScore,
                           boolean gated,
                           int evidenceCount,
                           String standaloneQuestion,
                           long elapsedMs) {
    }

    /**
     * 提问 → 回答。
     *
     * @param question 用户问题
     * @param topK     检索几个块当资料
     */
    public QaAnswer ask(String question, int topK) {
        return ask(question, topK, List.of());
    }

    /**
     * 提问 → 回答（带多轮历史）。
     *
     * <p><b>历史在这一步只做一件事</b>：让模型看懂"那留校察看呢"里的"那"指的是什么。
     * 它<b>不是事实来源</b>——上一轮模型自己说的话也可能是错的，拿它当依据会把错误一轮轮放大。
     * 所以提示词里明确写了历史不得引用，事实仍然只能来自本轮检索到的【资料】。
     *
     * @param history 由 {@link ContextAssembler} 截断好的最近几轮对话，可为空
     */
    public QaAnswer ask(String question, int topK, List<ContextAssembler.Turn> history) {
        long begin = System.currentTimeMillis();
        if (history != null && !history.isEmpty()) {
            log.info("多轮：携带 {} 条历史（只用于理解指代）：{}",
                    history.size(), ContextAssembler.describe(history));
        }

        Prepared prepared = prepare(question, topK, history, begin);
        if (prepared.earlyAnswer() != null) {
            return prepared.earlyAnswer();     // 没检索到 / 分数低于阈值：检索层就拒答，不花钱
        }
        List<RetrievalService.Hit> hits = prepared.best().hits();

        // ---------- 第 2~3 步：生成回答 ----------
        // 两段式（默认关闭）：①先只摘原句 ②再只用原句组答案。详见 docs/16。
        int evidenceCount = 0;
        String answer;
        if (llmProperties.isTwoStage()) {
            List<PromptBuilder.Quote> quotes = extractQuotes(question, hits);
            evidenceCount = quotes.size();
            if (quotes.isEmpty()) {
                // 第一步一条都没摘到：可能是资料确实不相关，也可能模型输出格式坏了。
                // 这种情况退回单段式，保证系统不会因为"第一步失败"而答不了。
                log.info("两段式第一步没摘出证据，回退单段式生成");
                answer = chatClient.chat(promptBuilder.buildMessages(question, hits, history));
            } else {
                log.debug("两段式：摘到 {} 条原句，用它组答案", quotes.size());
                answer = chatClient.chat(promptBuilder.buildAnswerMessagesFromQuotes(question, quotes, hits));
            }
        } else {
            answer = chatClient.chat(promptBuilder.buildMessages(question, hits, history));
        }

        Validated validated = validate(answer, hits);
        return build(question, answer, hits, prepared, validated, evidenceCount, begin);
    }

    /**
     * 流式问答：边生成边把文字增量交出去。
     *
     * <p>与 {@link #ask} 的关系：检索、拒答判定、引用校验、接地校验全部<b>共用同一套代码</b>
     * （{@link #prepare} + {@link #validate}），只有"生成"这一步从"等整段"换成"边收边给"。
     * 这样两条路的结果标准不会跑偏。
     *
     * <p>两个刻意的取舍：
     * <ul>
     *   <li><b>不走两段式</b>：两段式的第一步要等模型输出完整 JSON，天然没有流式可给；
     *       两段式默认就是关的，流式路径固定单段式。</li>
     *   <li><b>先回调 hits 再生成</b>：检索那 0.5~1.5 秒用户本来只能干等，
     *       先把"查到了哪几条资料"发出去，界面就不会像卡死。</li>
     * </ul>
     *
     * @param onHits  检索完成时回调（拒答时也可能是空列表）
     * @param onToken 每段文字回调一次
     */
    public QaAnswer stream(String question, int topK, List<ContextAssembler.Turn> history,
                           Consumer<List<RetrievalService.Hit>> onHits,
                           Consumer<String> onToken) {
        long begin = System.currentTimeMillis();
        if (history != null && !history.isEmpty()) {
            log.info("多轮：携带 {} 条历史（用于理解指代）：{}",
                    history.size(), ContextAssembler.describe(history));
        }

        Prepared prepared = prepare(question, topK, history, begin);
        onHits.accept(prepared.best() == null ? List.of() : prepared.best().hits());
        if (prepared.earlyAnswer() != null) {
            return prepared.earlyAnswer();
        }
        List<RetrievalService.Hit> hits = prepared.best().hits();
        if (llmProperties.isTwoStage()) {
            log.info("两段式开启时流式路径仍按单段式生成（两段式第一步要等完整 JSON，没有流式可给）");
        }

        String answer = chatClient.chatStream(promptBuilder.buildMessages(question, hits, history), onToken);
        Validated validated = validate(answer, hits);
        return build(question, answer, hits, prepared, validated, 0, begin);
    }

    /** 检索 + 拒答判定（两条生成路径共用）。 */
    private Prepared prepare(String question, int topK, List<ContextAssembler.Turn> history, long begin) {
        // ---------- 第 0 步：追问补全（多轮专用，只在"这句话自己检索不明白"时才做）----------
        // 先按原句检索一次：分数高说明这句话自己就够清楚，没必要补（省一次模型调用 + 一次检索）
        // 分数低 + 有历史 → 才用历史把它补成完整问题，再检索一次
        String standaloneQuestion = null;
        RetrievalService.Best best;
        if (history != null && !history.isEmpty() && followupTriggerScore > 0) {
            best = retrievalService.searchBestGated(question, topK);
            if (best.topScore() < followupTriggerScore && !best.gated()) {
                String completed = queryRewriter.rewriteFollowUp(question, history);
                if (completed != null && !completed.equals(question)) {
                    RetrievalService.Best second = retrievalService.searchBestGated(completed, topK);
                    // 只有"补全后确实更好"才采用，否则保留原结果——
                    // 补全也是模型在猜，猜错了不能把原本对的结果弄坏
                    if (second.topScore() > best.topScore()) {
                        log.info("追问补全生效：「{}」+ 历史 → 「{}」，topScore {} → {}",
                                question, completed, best.topScore(), second.topScore());
                        standaloneQuestion = completed;
                        best = second;
                    } else {
                        log.info("追问补全没提升，保留原检索：{} → {}（{} vs {}）",
                                question, completed, second.topScore(), best.topScore());
                    }
                }
            }
        } else {
            best = retrievalService.searchBestGated(question, topK);
        }

        // 一条都没检索到：连资料都没有，直接拒答（不花钱）
        if (best.hits().isEmpty()) {
            return new Prepared(best, standaloneQuestion, new QaAnswer(question,
                    "本知识库暂未收录与该问题相关的规定，建议咨询学校相关职能部门或查看学校官网。",
                    chatClient.model(), List.of(), 0, true, List.of(),
                    true, 0, List.of(), 0.0, true, 0, standaloneQuestion,
                    System.currentTimeMillis() - begin));
        }

        // 检索分数低于阈值：资料太不相关，问了模型也只能编 → 在检索层就拒答
        // 这是"宁可说没查到，也不猜"的工程化实现，见 docs/09-拒答阈值
        if (best.gated()) {
            return new Prepared(best, standaloneQuestion, new QaAnswer(question,
                    "本知识库暂未收录与该问题相关的规定（检索置信度不足，最高相关度 "
                            + best.topScore() + "，低于阈值 " + best.threshold() + "）。\n\n"
                            + "你可以换一种问法重试，也可以直接咨询学校相关职能部门。",
                    chatClient.model(), List.of(), best.hits().size(), true, List.of(),
                    true, 0, List.of(), best.topScore(), true, 0, standaloneQuestion,
                    System.currentTimeMillis() - begin));
        }
        return new Prepared(best, standaloneQuestion, null);
    }

    /**
     * 校验 + 组装（两条生成路径共用）。
     *
     * <p>第 4 步校验引用编号：模型有可能会写出资料里不存在的编号（比如只有 3 条却标 [5]），
     * 前端就会显示一个点不开的"出处"，用户信任瞬间崩塌。
     *
     * <p>第 5 步接地校验：编号合法不等于出处正确。真实案例——问"违纪处分有哪几种"，
     * 模型把五种处分答对了却标到 [2]，而清单其实在第 3 块里（被切分劈到了下一块）。
     */
    private Validated validate(String answer, List<RetrievalService.Hit> hits) {
        List<Integer> cited = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= hits.size()) {
                if (!cited.contains(n)) {
                    cited.add(n);
                }
            } else if (!invalid.contains(n)) {
                invalid.add(n);
            }
        }
        // 语义：citationOk 只表示『没有编造出资料里不存在的编号』。
        // 拒答/无据回答本来就不该带编号，不能因为『没引用』判成『校验不通过』——
        // 是否真的引用了资料，看 references 的条数（前端显示『未引用资料』）。
        boolean citationOk = invalid.isEmpty();
        if (!invalid.isEmpty()) {
            log.warn("模型编造了不存在的引用编号 {}，资料只有 {} 条", invalid, hits.size());
        }

        List<String> citedContents = new ArrayList<>();
        for (int n : cited) {
            citedContents.add(hits.get(n - 1).content());
        }
        AnswerGuard.Check guard = AnswerGuard.check(answer, citedContents);
        // factCount==0 表示这段回答里没有可核对的硬事实，此时不判"不合格"，只如实上报"核了 0 条"
        if (!guard.missing().isEmpty()) {
            log.warn("回答里有 {} 处事实在被引用原文中找不到：{}", guard.missingCount(), guard.missing());
        }

        // 第 6 步：只返回"回答里真的用到了"的引用，没用到的就不显示，避免噪音
        List<Reference> references = new ArrayList<>();
        for (int n : cited) {
            RetrievalService.Hit h = hits.get(n - 1);
            references.add(new Reference(n, h.chunkId(), h.documentTitle(), h.chunkIndex(),
                    h.score(), h.content()));
        }
        return new Validated(cited, invalid, citationOk, guard, references);
    }

    /** 把 16 个字段拼成一个 QaAnswer（两条路径共用，避免字段顺序写错） */
    private QaAnswer build(String question, String answer, List<RetrievalService.Hit> hits,
                           Prepared prepared, Validated validated, int evidenceCount, long begin) {
        AnswerGuard.Check guard = validated.guard();
        return new QaAnswer(question, answer, chatClient.model(), validated.references(), hits.size(),
                validated.citationOk(), validated.invalid(), guard.missing().isEmpty(),
                guard.factCount(), guard.missing(), prepared.best().topScore(), false,
                evidenceCount, prepared.standaloneQuestion(), System.currentTimeMillis() - begin);
    }

    /** 检索阶段的结果：earlyAnswer 不为 null 表示"检索层就决定了不生成" */
    private record Prepared(RetrievalService.Best best, String standaloneQuestion, QaAnswer earlyAnswer) {
    }

    /** 校验阶段的结果 */
    private record Validated(List<Integer> cited, List<Integer> invalid, boolean citationOk,
                             AnswerGuard.Check guard, List<Reference> references) {
    }

    /**
     * 两段式的第一步：让模型只从资料里逐字摘出能回答问题的原句。
     *
     * <p>两道保险：
     * ① 格式保险 —— 模型可能不老实输出 JSON（带解释、套代码块），所以只截取第一个 '[' 到最后一个 ']'。
     * ② 忠实保险 —— 摘出来的句子必须能在对应块里找到（忽略空白与标点差异），
     * 找不到说明模型改写了，直接丢弃。宁可少一条证据，也不能把改写过的句子当原文。
     */
    private List<PromptBuilder.Quote> extractQuotes(String question, List<RetrievalService.Hit> hits) {
        try {
            String raw = chatClient.chat(promptBuilder.buildExtractMessages(question, hits));
            int from = raw.indexOf('[');
            int to = raw.lastIndexOf(']');
            if (from < 0 || to <= from) {
                log.warn("第一步输出里没有 JSON 数组，原始输出：{}", abbreviate(raw));
                return List.of();
            }
            JsonNode arr = objectMapper.readTree(raw.substring(from, to + 1));
            if (!arr.isArray()) {
                return List.of();
            }
            List<PromptBuilder.Quote> quotes = new ArrayList<>();
            for (JsonNode node : arr) {
                int n = node.path("n").asInt(0);
                String quote = node.path("quote").asText("").trim();
                if (n < 1 || n > hits.size() || quote.isEmpty()) {
                    continue;
                }
                String content = hits.get(n - 1).content();
                if (!looseContains(content, quote)) {
                    log.warn("第一步摘的句子在资料 [{}] 里找不到，丢弃：{}", n, abbreviate(quote));
                    continue;
                }
                quotes.add(new PromptBuilder.Quote(n, quote));
                if (quotes.size() >= 5) {
                    break;
                }
            }
            return quotes;
        } catch (Exception e) {
            log.warn("第一步（摘原句）失败，回退单段式：{}", e.getMessage());
            return List.of();
        }
    }

    /** 宽松包含：忽略空白、标点、全半角差异后再比，避免"引用时多打一个句号"被误判为改写。 */
    private static boolean looseContains(String content, String quote) {
        String c = normalizeForCompare(content);
        String q = normalizeForCompare(quote);
        return q.length() >= 8 && c.contains(q);
    }

    private static String normalizeForCompare(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if ("，。、；：？！,.;:?!“”‘’（）()《》【】·-—…\"'".indexOf(ch) >= 0) {
                continue;
            }
            sb.append(Character.isDigit(ch) ? ch : ch);
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    /** 调试用：只看检索 + 拼出来的提示词，不调模型（不花钱）。 */
    public Map<String, Object> preview(String question, int topK) {
        return preview(question, topK, List.of());
    }

    /** 调试用（带多轮历史）：能看见这次除了资料，模型还看到了哪几句历史。 */
    public Map<String, Object> preview(String question, int topK, List<ContextAssembler.Turn> history) {
        List<RetrievalService.Hit> hits = retrievalService.searchBest(question, topK);
        Map<String, Object> data = new LinkedHashMap<>(promptBuilder.preview(question, hits, history));
        data.put("hitCount", hits.size());
        data.put("historyCount", history == null ? 0 : history.size());
        data.put("hits", hits);
        return data;
    }
}
