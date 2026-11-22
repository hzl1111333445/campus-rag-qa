package com.campus.rag.service;

import com.campus.rag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final ObjectMapper objectMapper;

    public QaService(RetrievalService retrievalService,
                     PromptBuilder promptBuilder,
                     ChatClient chatClient,
                     LlmProperties llmProperties,
                     ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.llmProperties = llmProperties;
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
     * @param citationOk        回答里的编号是否全部合法（没有编造）
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

        // ---------- 第 1 步：检索（混合粗排 → 精排 → 展开邻居，详见 docs/08）----------
        RetrievalService.Best best = retrievalService.searchBestGated(question, topK);
        List<RetrievalService.Hit> hits = best.hits();

        // 一条都没检索到：连资料都没有，直接拒答（不花钱）
        if (hits.isEmpty()) {
            return new QaAnswer(question,
                    "本知识库暂未收录与该问题相关的规定，建议咨询学校相关职能部门或查看学校官网。",
                    chatClient.model(), List.of(), 0, true, List.of(),
                    true, 0, List.of(), 0.0, true, 0,
                    System.currentTimeMillis() - begin);
        }

        // 检索分数低于阈值：资料太不相关，问了模型也只能编 → 在检索层就拒答
        // 这是"宁可说没查到，也不猜"的工程化实现，见 docs/09-拒答阈值
        if (best.gated()) {
            return new QaAnswer(question,
                    "本知识库暂未收录与该问题相关的规定（检索置信度不足，最高相关度 "
                            + best.topScore() + "，低于阈值 " + best.threshold() + "）。\n\n"
                            + "你可以换一种问法重试，也可以直接咨询学校相关职能部门。",
                    chatClient.model(), List.of(), hits.size(), true, List.of(),
                    true, 0, List.of(), best.topScore(), true, 0,
                    System.currentTimeMillis() - begin);
        }

        // ---------- 第 2~3 步：生成回答 ----------
        // 两段式（默认）：①先只摘原句 ②再只用原句组答案。详见 docs/16。
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

        // ---------- 第 4 步：校验引用编号 ----------
        // 为什么必须校验？模型有可能会写出资料里不存在的编号（比如只有 3 条却标 [5]），
        // 前端就会显示一个点不开的"出处"，用户信任瞬间崩塌。
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
        boolean citationOk = invalid.isEmpty() && !cited.isEmpty();
        if (!invalid.isEmpty()) {
            log.warn("模型编造了不存在的引用编号 {}，资料只有 {} 条", invalid, hits.size());
        }

        // ---------- 第 5 步：接地校验（groundedness）----------
        // 编号校验只能保证"第 n 条引用存在"，不能保证"这个事实真的来自第 n 条引用"。
        // 真实案例：问"违纪处分有哪几种"，模型把五种处分答对了，却标到 [2]，
        // 而那份清单其实在第 3 块里（被切分劈到了下一块）。编号合法，但出处是假的。
        // 做法：从回答里抽"硬事实"（数字+单位、时间点、处分名称），逐个到被引用块的原文里找。
        List<String> citedContents = new ArrayList<>();
        for (int n : cited) {
            citedContents.add(hits.get(n - 1).content());
        }
        AnswerGuard.Check guard = AnswerGuard.check(answer, citedContents);
        // factCount==0 表示这段回答里没有可核对的硬事实，此时不判"不合格"，只如实上报"核了 0 条"
        boolean grounded = guard.missing().isEmpty();
        if (!guard.missing().isEmpty()) {
            log.warn("回答里有 {} 处事实在被引用原文中找不到：{}", guard.missingCount(), guard.missing());
        }

        // ---------- 第 6 步：组装引用列表 ----------
        // 只返回"回答里真的用到了"的引用，没用到的就不显示，避免噪音
        List<Reference> references = new ArrayList<>();
        for (int n : cited) {
            RetrievalService.Hit h = hits.get(n - 1);
            references.add(new Reference(n, h.chunkId(), h.documentTitle(), h.chunkIndex(),
                    h.score(), h.content()));
        }

        return new QaAnswer(question, answer, chatClient.model(), references, hits.size(),
                citationOk, invalid, grounded, guard.factCount(), guard.missing(),
                best.topScore(), false, evidenceCount, System.currentTimeMillis() - begin);
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
