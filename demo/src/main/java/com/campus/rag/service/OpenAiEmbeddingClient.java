package com.campus.rag.service;

import com.campus.rag.config.EmbeddingProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真·向量化实现：调 OpenAI 兼容的 /embeddings 接口（硅基流动、阿里云百炼、OpenAI 都是这个格式）。
 *
 * <p>请求长这样：
 * <pre>
 * POST https://api.siliconflow.cn/v1/embeddings
 * Authorization: Bearer sk-xxxx
 * {"model": "BAAI/bge-m3", "input": ["第一段文字", "第二段文字"]}
 * </pre>
 *
 * <p>返回长这样（data 里每条对应一条输入）：
 * <pre>
 * {"data": [{"index": 0, "embedding": [0.0199, 0.0422, ...]}, ...]}
 * </pre>
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    private final EmbeddingProperties props;
    private final RestClient http;

    public OpenAiEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
        // RestClient 是 Spring 6.1 之后推荐的同步 HTTP 客户端，比 RestTemplate 写法更顺
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String model() {
        return props.getModel();
    }

    @Override
    public int dim() {
        return props.getDim();
    }

    @Override
    public double[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 一次请求发多条：305 个块只发 20 次请求（每批 16 条），比一条一条发快一个数量级。
     */
    @Override
    public List<double[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("input", texts);

        EmbeddingResponse resp;
        try {
            resp = http.post()
                    .uri("/embeddings")
                    .body(body)
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientResponseException e) {
            // 把服务端返回的原始报错带出来，否则你只会看到一句没用的 400
            String detail = e.getResponseBodyAsString();
            throw new IllegalStateException("调用向量化接口失败：" + e.getStatusCode() + " " + explain(detail));
        }

        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            throw new IllegalStateException("向量化接口返回了空结果");
        }

        // data 的顺序不一定是输入顺序，所以按 index 排序后再取
        List<Item> items = new ArrayList<>(resp.data());
        items.sort(Comparator.comparingInt(Item::index));

        List<double[]> result = new ArrayList<>(items.size());
        for (Item item : items) {
            List<Double> raw = item.embedding();
            if (raw.size() != props.getDim()) {
                throw new IllegalStateException("维度不符：配置写的是 " + props.getDim()
                        + "，但模型返回了 " + raw.size() + " 维。请改 application.yml 里的 campus.embedding.dim");
            }
            double[] v = new double[raw.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = raw.get(i);
            }
            result.add(normalize(v));
        }

        log.debug("向量化完成：{} 条，模型 {}，维度 {}", result.size(), model(), dim());
        return result;
    }

    /** 归一化：让向量长度为 1。这样后面算余弦相似度就等于算点积，省一次开方。 */
    private static double[] normalize(double[] v) {
        double sum = 0;
        for (double x : v) {
            sum += x * x;
        }
        if (sum <= 0) {
            return v;
        }
        double len = Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = v[i] / len;
        }
        return v;
    }

    /** 把常见的几种报错翻译成人话。 */
    private static String explain(String body) {
        String b = body == null ? "" : body.toLowerCase();
        if (b.contains("401") || b.contains("invalid") && b.contains("key") || b.contains("unauthorized")) {
            return "API Key 不对或已失效，检查 application-local.yml";
        }
        if (b.contains("403") || b.contains("forbidden")) {
            return "这个 key 没有调用该模型的权限";
        }
        if (b.contains("429") || b.contains("rate")) {
            return "请求太频繁，被限流了，把 batch-size 调小或稍后再试";
        }
        if (b.contains("insufficient") || b.contains("balance")) {
            return "额度不足";
        }
        String s = body == null ? "" : body;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ==================== 接口返回的数据结构 ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(int index, List<Double> embedding) {
    }
}
