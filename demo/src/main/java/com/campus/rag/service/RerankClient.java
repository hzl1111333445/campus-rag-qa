package com.campus.rag.service;

import com.campus.rag.config.RerankProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精排客户端：把「问题 + 若干候选块」送给 Cross-Encoder 模型，拿回每条的相关性分数。
 *
 * <p><b>它和向量检索的本质区别</b>：
 * <ul>
 *   <li>向量检索（Bi-Encoder）：问题和块<b>各自</b>算成一个向量，再比余弦。
 *       好处是可以提前把 414 个块都算好（入库时），查询只要算 1 个向量，很快。
 *       坏处是"问题和块的关系"没被建模过。</li>
 *   <li>精排（Cross-Encoder）：把问题和块<b>拼在一起</b>送进模型，
 *       模型能直接看到"问题里的'多少钱'对上了块里的'10000元'"。
 *       所以准，但必须一条一条算，快不了。</li>
 * </ul>
 *
 * <p><b>失败必须降级</b>：网络抖动、额度用完、超时都可能发生。
 * 这时不能让用户看到 500，而是返回 null，由调用方退回粗排顺序（体验略差但能用）。
 */
@Service
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final RerankProperties props;
    private final RestClient restClient;

    public RerankClient(RerankProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())));
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .build();
    }

    /** 是否可用：开关打开 + 有 key */
    public boolean available() {
        return props.isEnabled() && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    public String modelName() {
        return props.getModel();
    }

    /**
     * 给一批文档打相关性分数。
     *
     * @param query     用户问题
     * @param documents 候选块正文（调用方负责截断，见 {@link #truncate}）
     * @return 与 documents 一一对应的分数；不可用或失败时返回 <b>null</b>（调用方据此降级）
     */
    public double[] scores(String query, List<String> documents) {
        if (!available() || documents.isEmpty()) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("query", query);
        body.put("documents", documents);
        // top_n 不设的话有的实现只返回前 10 条；显式要求全返回，方便我们自己取前 K
        body.put("top_n", documents.size());

        try {
            long begin = System.currentTimeMillis();
            Resp resp = restClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Resp.class);

            if (resp == null || resp.results() == null || resp.results().isEmpty()) {
                log.warn("精排返回空结果，降级为粗排顺序");
                return null;
            }

            double[] scores = new double[documents.size()];
            for (Item item : resp.results()) {
                if (item.index() >= 0 && item.index() < scores.length) {
                    scores[item.index()] = item.score();
                }
            }
            log.info("精排完成：{} 条候选，耗时 {}ms", documents.size(), System.currentTimeMillis() - begin);
            return scores;

        } catch (RuntimeException e) {
            log.warn("精排失败，降级为粗排顺序：{}", e.getMessage());
            return null;
        }
    }

    /** 截断单条文档，避免超长把模型输入撑爆（长文本主要信息都在开头） */
    public String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = Math.max(100, props.getMaxDocChars());
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 把分数数组按降序转成下标顺序（同分按下标升序，保证结果可复现） */
    public static List<Integer> orderByScoreDesc(double[] scores) {
        List<Integer> idx = new ArrayList<>(scores.length);
        for (int i = 0; i < scores.length; i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> {
            int cmp = Double.compare(scores[b], scores[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        return idx;
    }

    // ================= 响应结构 =================
    // 只声明用得到的字段，多了不管（@JsonIgnoreProperties(ignoreUnknown = true)）

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resp(List<Item> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(int index, @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double score) {
    }
}
