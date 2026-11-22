package com.campus.rag.service;

import com.campus.rag.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 查询改写（Query Rewrite）：把学生的口语问题翻译成规章文件里的书面表达。
 *
 * <p><b>为什么需要它</b>：学生问「忘还了怎么算」，规章里写的是「逾期归还」。
 * 字面检索（BM25）完全匹配不上，向量也常常连不上——
 * 这是"硬改写"题只有 70% 召回的主因。
 *
 * <p><b>为什么用大模型而不是同义词表</b>：
 * 同义词表只能覆盖写死的词对（忘还→逾期），而学生说法是无穷的。
 * 大模型是"翻译器"，不是"检索器"，它只看这一句话（几十个 token），
 * 不碰语料，所以成本极低（每次约 60 输入 + 30 输出 token）。
 *
 * <p><b>为什么要有开关和缓存</b>：
 * <ol>
 *   <li>关掉时系统行为与之前完全一致（可对照）</li>
 *   <li>缓存让同一个问题第二次改写 0 成本、0 延迟</li>
 *   <li>调用失败返回 null，由调用方退回原有逻辑——改写不能成为新的故障点</li>
 * </ol>
 */
@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    /**
     * 系统提示词：只做"口语 → 规章书面语"的翻译。
     *
     * <p>三条约束都是防止改写跑偏：
     * <ol>
     *   <li>不许改变问题意图（不许把"能不能"改成"怎么办理"）</li>
     *   <li>不许编造原文没有的概念（只换说法，不换问题）</li>
     *   <li>只输出一句话，避免模型输出解释性文字污染检索</li>
     * </ol>
     */
    private static final String SYSTEM_PROMPT = """
            你是校园规章制度检索系统的「问题改写器」。
            用户会给你一句学生的大白话提问，你要把它改写为学校规章文件里常用的书面表达，
            以便字面检索能命中。要求：
            1. 保持原问题的意图和关键实体（时间、数字、对象、事项），不许增加或删掉条件；
            2. 只替换用词，不改变问题的类型（问"能不能"还是问"怎么办"）；
            3. 不要编造原文没有的概念，不确定时保留原词；
            4. 只输出改写后的一句话，不要解释、不要引号、不要编号。
            """;

    private static final int MAX_REWRITE_CHARS = 80;
    private static final int MAX_TOKENS = 96;

    private final LlmProperties properties;
    private final RestClient restClient;
    private final boolean enabled;
    private final int cacheSize;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public QueryRewriter(LlmProperties properties,
                         @Value("${campus.rewrite.enabled:false}") boolean enabled,
                         @Value("${campus.rewrite.timeout-seconds:8}") int timeoutSeconds,
                         @Value("${campus.rewrite.cache-size:500}") int cacheSize) {
        this.properties = properties;
        this.enabled = enabled;
        this.cacheSize = Math.max(50, cacheSize);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @PostConstruct
    void logState() {
        if (enabled) {
            log.info("查询改写已启用：model={}，缓存上限 {} 条", properties.getModel(), cacheSize);
        } else {
            log.info("查询改写未启用（campus.rewrite.enabled=false），检索行为与之前一致");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String modelName() {
        return properties.getModel();
    }

    /**
     * 把问题改写成书面表达。
     *
     * @return 改写后的问句；未启用、调用失败、结果不合理时返回 {@code null}
     */
    public String rewrite(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return null;
        }
        String cached = cache.get(question);
        if (cached != null) {
            return cached;
        }
        String rewritten = callModel(question);
        if (rewritten != null) {
            if (cache.size() >= cacheSize) {
                cache.clear();
            }
            cache.put(question, rewritten);
        }
        return rewritten;
    }

    private String callModel(String question) {
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", question)),
                    "temperature", 0.0,
                    "max_tokens", MAX_TOKENS,
                    "stream", false);

            RewriteResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(RewriteResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                return null;
            }
            String text = sanitize(response.choices().get(0).message().content());
            if (text == null || text.equals(question)) {
                return null;
            }
            return text;
        } catch (RuntimeException e) {
            // 改写是"增强"而不是"必需"，失败就退回原句，绝不让问答失败
            log.warn("查询改写失败，本次退回原问题：{}", e.getMessage());
            return null;
        }
    }

    /** 去掉模型偶尔带出来的引号、前缀、换行；太长的一律不用（说明它开始解释了） */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        s = s.replace("```", "").strip();
        for (String prefix : List.of("改写：", "改写:", "答：", "答:", "问题：", "问题:")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).strip();
            }
        }
        s = s.replaceAll("^[\"'“”‘’《》\\s]+", "").replaceAll("[\"'“”‘’《》\\s]+$", "");
        s = s.replace('\n', ' ').strip();
        if (s.isEmpty() || s.length() > MAX_REWRITE_CHARS) {
            return null;
        }
        return s;
    }

    record RewriteResponse(List<Choice> choices) {
    }

    record Choice(Message message) {
    }

    record Message(String content) {
    }
}
