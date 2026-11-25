package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询分解器：把一个"复合问题"拆成几个"单一子问题"，让每一路各自去检索。
 *
 * <p><b>为什么需要它？</b>现在的链路是"一次检索"：一句话 → 一个查询 → Top3 块。
 * 遇到"助学贷款要什么材料、去哪个部门办"这种一句话里有两个意图的问题时，
 * 两个意图会挤在同一个查询里互相稀释——实测 Top1 直接跑偏到"入伍学费补偿"，
 * 真正该命中的《资助办法》根本没进前 3。这不是模型笨，是检索只被问了一次。
 *
 * <p><b>为什么不用 ReAct？</b>这个场景不需要"看了结果再决定下一步"，
 * 只需要"一次问几个问题"。查询分解比 ReAct 便宜得多（只多 1 次轻量模型调用），
 * 也不会把 120 题单跳评测拖慢。
 *
 * <p><b>兜底原则：</b>分解失败、或拆出来的子问题和原句差不多时，**一律退回原句**——
 * 宁可不分解，也不能把原本能答对的问题弄坏。
 */
@Component
public class QueryDecomposer {

    private static final Logger log = LoggerFactory.getLogger(QueryDecomposer.class);

    /** 子问题的字数上限：太长说明模型在"改写"而不是"拆分"，直接判定失败。 */
    private static final int MAX_SUB_QUERY_CHARS = 60;

    private static final String SYSTEM_PROMPT = """
            你是校园规章检索的"问题拆分器"。用户会给你一个可能包含多个意图的问题。

            任务：把它拆成若干个**各自独立可检索**的子问题。规则：
            1. 只在确实有多个意图时才拆；单一问题原样输出一行，不要为了凑数硬拆；
            2. 每个子问题必须能单独拿去检索，不要用"它""这个""上述"等指代；
            3. 保留原问题里的关键实体（专业、处分、金额、部门等），不要编造没提到的信息；
            4. 最多 %d 行，每行一个子问题，不加编号、不加标点以外的解释；
            5. 只输出子问题本身，不要输出"问题1："这类前缀。
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int maxQueries;
    /** 同一个问题重复提问时不必再调模型（用户常常"再问一遍"）。 */
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public QueryDecomposer(ChatClient chatClient,
                           @Value("${campus.decompose.enabled:false}") boolean enabled,
                           @Value("${campus.decompose.max-queries:3}") int maxQueries) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.maxQueries = Math.max(1, Math.min(maxQueries, 5));
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 拆问题。返回值**永远非空**：退化情况返回只含原句的列表。
     *
     * @param question 用户原始问题
     * @return 至少一个查询串；第一个永远是原句（保证"不分解也能答"）
     */
    public List<String> decompose(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return List.of(question);
        }
        // 先用便宜的规则筛一遍：太短的、或"一眼单一意图"的问题，不必花一次模型调用。
        // 120 题单跳集里 81 题长度 < 12，如果真的每题都调，平均延迟会多出 0.5s 却没有任何收益。
        if (!looksCompound(question)) {
            return List.of(question);
        }
        List<String> cached = cache.get(question);
        if (cached != null) {
            return cached;
        }
        List<String> result = doDecompose(question);
        if (cache.size() > 500) {
            cache.clear();// 简单粗暴：缓存不是关键路径，撑大了直接清
        }
        cache.put(question, result);
        return result;
    }

    /**
     * 便宜的复合句预筛：不调模型，只看标点与连词。
     *
     * <p>依据：真正需要拆的问题，几乎都带并列/转折的痕迹
     * （"助学贷款要什么材料，去哪个部门办" / "转专业挂了两门，还能申请吗"）。
     * 反过来，单意图问题很少同时满足"够长 + 有并列"两个条件。
     */
    private boolean looksCompound(String question) {
        if (question.length() < 12) {
            return false;
        }
        int separators = 0;
        for (char c : question.toCharArray()) {
            if (c == '，' || c == ',' || c == '；' || c == ';' || c == '、' || c == '？' || c == '?') {
                separators++;
            }
        }
        // 一个逗号就算复合（"…怎么办，需要什么"），两个以上问号/顿号同样算
        return separators >= 1
                || question.contains("并且") || question.contains("还有")
                || question.contains("另外") || question.contains("同时")
                || question.contains("以及");
    }

    private List<String> doDecompose(String question) {
        try {
            String raw = chatClient.chat(List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT.formatted(maxQueries)),
                    Map.of("role", "user", "content", question)));
            List<String> subs = parse(raw, question);
            if (subs.size() <= 1) {
                return List.of(question);
            }
            log.info("查询分解：「{}」→ {}", question, subs);
            return subs;
        } catch (Exception e) {
            // 分解是"增强"，失败必须静默退回原句，不能影响主链路
            log.warn("查询分解失败，退回原句：{}（{}）", question, e.getMessage());
            return List.of(question);
        }
    }

    /**
     * 解析模型输出。
     *
     * <p>只做同一件事：按行切、去编号、去空行、限长、去重。
     * 第一行放原句，让检索至少保底用原句查一遍。
     */
    private List<String> parse(String raw, String question) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(question);
        if (raw == null) {
            return new ArrayList<>(out);
        }
        for (String line : raw.split("\\r?\\n")) {
            String s = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.、)]|问题\\s*\\d+[:：])\\s*", "").trim();
            if (s.isEmpty()) {
                continue;
            }
            // 模型偶尔会带上"如果…请补充"这类废话，超过字数就当它没拆
            if (s.length() > MAX_SUB_QUERY_CHARS) {
                continue;
            }
            if (s.equals(question)) {
                continue;
            }
            out.add(s);
            if (out.size() > maxQueries) {
                break;
            }
        }
        return new ArrayList<>(out);
    }
}
