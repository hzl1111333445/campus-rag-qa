package com.campus.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 给向量化客户端加一层缓存（装饰器模式）。
 *
 * <p><b>为什么在这里加，而不是在业务代码里加</b>：
 * 向量化有两个入口——检索时的"问题向量化"（每次提问一次）和入库时的"批量向量化"
 * （每次上传或重处理一次）。如果在两个调用点各加一遍缓存，就会出现"一处忘了加、
 * 一处 TTL 不一样"的分裂。套在客户端外面，两个入口自动都有缓存。
 *
 * <p><b>收益在哪</b>：问题向量化是一次真实的 HTTP 请求（硅基流动 bge-m3，
 * 本机实测 150~300ms）。同一个问题第二次问，这一步可以直接省掉。
 * 注意它省的是<b>网络往返</b>，不是模型推理——向量结果只跟文字有关，天然幂等，
 * 所以缓存是安全的（不像问答结果要考虑资料是否变了）。
 *
 * <p><b>为什么键里要带模型名</b>：换 embedding 模型后，同一个句子的向量完全不同。
 * 键里不带模型名，就会出现"换了模型但检索结果还是旧模型的"——这种 bug 极难发现。
 */
public class CachedEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(CachedEmbeddingClient.class);

    private final EmbeddingClient delegate;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public CachedEmbeddingClient(EmbeddingClient delegate, CacheService cacheService,
                                 ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public int dim() {
        return delegate.dim();
    }

    /** 单条：先查缓存，未命中再调真模型，成功后写回。 */
    @Override
    public double[] embed(String text) {
        String key = key(text);
        if (key == null) {
            return delegate.embed(text);
        }
        String cached = cacheService.get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, double[].class);
            } catch (Exception e) {
                log.warn("向量缓存解析失败，改走真模型：{}", e.getMessage());
            }
        }
        double[] vector = delegate.embed(text);
        put(key, vector);
        return vector;
    }

    /**
     * 批量：逐条查缓存，只把未命中的交给真模型，最后按原顺序拼回去。
     *
     * <p>顺序必须保持——调用方是按下标把向量写回数据库的（第 i 条向量属于第 i 个块），
     * 一旦错位，向量和文本就对不上了（入库时踩过这个坑）。
     */
    @Override
    public List<double[]> embedBatch(List<String> texts) {
        List<double[]> out = new ArrayList<>(texts.size());
        List<Integer> missIndexes = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        // 先把能命中的填好，命不中的记下标，位置留 null 占位
        for (int i = 0; i < texts.size(); i++) {
            out.add(null);
        }
        for (int i = 0; i < texts.size(); i++) {
            String key = key(texts.get(i));
            boolean hit = false;
            if (key != null) {
                String cached = cacheService.get(key);
                if (cached != null) {
                    try {
                        out.set(i, objectMapper.readValue(cached, double[].class));
                        hit = true;
                    } catch (Exception e) {
                        log.warn("向量缓存解析失败，按未命中处理：{}", e.getMessage());
                    }
                }
            }
            if (!hit) {
                missIndexes.add(i);
                missTexts.add(texts.get(i));
            }
        }
        if (missTexts.isEmpty()) {
            log.debug("批量向量化全部命中缓存：{} 条", texts.size());
            return out;
        }
        List<double[]> computed = delegate.embedBatch(missTexts);
        for (int j = 0; j < computed.size() && j < missIndexes.size(); j++) {
            int idx = missIndexes.get(j);
            out.set(idx, computed.get(j));
            put(key(missTexts.get(j)), computed.get(j));
        }
        log.info("批量向量化：{} 条，缓存命中 {}，实际请求 {}",
                texts.size(), texts.size() - missTexts.size(), missTexts.size());
        return out;
    }

    /** 键：模型名 + 文字（CacheKeys 内部会做归一化与哈希）。文本为空时不缓存。 */
    private String key(String text) {
        if (text == null || text.isBlank() || !cacheService.enabled()) {
            return null;
        }
        return CacheKeys.embedding(model(), text);
    }

    private void put(String key, double[] vector) {
        if (key == null || vector == null) {
            return;
        }
        try {
            cacheService.put(key, objectMapper.writeValueAsString(vector), cacheService.embeddingTtl());
        } catch (Exception e) {
            log.warn("向量缓存写入失败，忽略：{}", e.getMessage());
        }
    }
}
