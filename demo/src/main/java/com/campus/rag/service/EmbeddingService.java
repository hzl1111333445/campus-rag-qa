package com.campus.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把数据库里"还没有向量"的块，批量变成向量存回去。
 *
 * <p>为什么不放在上传流程里一起做？
 * 因为向量化要联网、慢（305 块要十几秒），如果放在上传接口里，
 * 用户点"上传"就得等十几秒。分开做，上传秒回，向量化后台补。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingClient client;
    private final DocumentDao documentDao;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public EmbeddingService(EmbeddingClient client,
                            DocumentDao documentDao,
                            ObjectMapper objectMapper,
                            @Value("${campus.embedding.batch-size:16}") int batchSize) {
        this.client = client;
        this.documentDao = documentDao;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 把所有还没有向量的块向量化。
     *
     * @param maxChunks 本次最多处理多少块（调试时先跑 10 块看看，省时间）
     */
    public Map<String, Object> embedPending(int maxChunks) {
        return embed(documentDao.listChunksNeedingEmbedding(maxChunks));
    }

    /**
     * 只补某一份文档的向量。
     *
     * <p>上传或重新切分完立刻调：新块的 embedding 是 NULL，向量检索看不见它们，
     * 只有 BM25 能命中 —— 表现就是"文档传上去了，问它却搜不到"。
     */
    public Map<String, Object> embedPendingOfDocument(long documentId, int maxChunks) {
        return embed(documentDao.listChunksNeedingEmbeddingByDocument(documentId, maxChunks));
    }

    /** 真正的向量化循环：拿一批待处理块，分批调接口，逐块写回。 */
    private Map<String, Object> embed(List<DocumentDao.ChunkForEmbed> pending) {
        long begin = System.currentTimeMillis();
        int embedded = 0;
        int batches = 0;
        String error = null;

        for (int i = 0; i < pending.size(); i += batchSize) {
            List<DocumentDao.ChunkForEmbed> slice = pending.subList(i, Math.min(i + batchSize, pending.size()));
            List<String> texts = slice.stream().map(DocumentDao.ChunkForEmbed::content).toList();
            try {
                List<double[]> vectors = client.embedBatch(texts);
                for (int j = 0; j < slice.size(); j++) {
                    documentDao.updateChunkEmbedding(
                            slice.get(j).id(), toJson(vectors.get(j)), client.model(), client.dim());
                    embedded++;
                }
                batches++;
            } catch (RuntimeException e) {
                // 出错就停：继续跑只会一直报同样的错，还浪费额度
                error = e.getMessage();
                log.warn("向量化中断：第 {} 批失败，{}", batches + 1, error);
                break;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", client.model());
        data.put("dim", client.dim());
        data.put("pendingBefore", pending.size());
        data.put("embeddedThisRun", embedded);
        data.put("batches", batches);
        data.put("chunksTotal", documentDao.countChunks());
        data.put("chunksEmbedded", documentDao.countEmbeddedChunks());
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        if (error != null) {
            data.put("error", error);
        }
        return data;
    }

    /** 把 double[] 变成 JSON 字符串，比如 [0.0199,0.0422,...]，直接存进 MEDIUMTEXT 列。 */
    private String toJson(double[] v) {
        // 保留 6 位小数就够了，1024 维 × 8 字符 ≈ 8KB，一列塞得下
        StringBuilder sb = new StringBuilder(v.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.6f", v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** 反向：JSON 字符串 → double[]（后面做检索时要用）。 */
    public double[] fromJson(String json) {
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量解析失败，数据可能损坏：" + e.getOriginalMessage());
        }
    }
}
