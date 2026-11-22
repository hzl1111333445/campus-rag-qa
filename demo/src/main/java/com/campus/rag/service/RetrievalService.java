package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 检索服务：输入一个问题，返回库里最像的 topK 个文本块。
 *
 * <p>这是 RAG 里 "R"（Retrieval，检索）那一步。
 * 后面的问答生成，就是把这几个块塞给大模型当资料。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddingClient;
    private final DocumentDao documentDao;
    private final EmbeddingService embeddingService;

    public RetrievalService(EmbeddingClient embeddingClient,
                            DocumentDao documentDao,
                            EmbeddingService embeddingService) {
        this.embeddingClient = embeddingClient;
        this.documentDao = documentDao;
        this.embeddingService = embeddingService;
    }

    /**
     * 一条检索结果。
     *
     * @param chunkId       块 id（以后做"引用溯源"要用它）
     * @param documentTitle 出自哪个文档（用户要看得出处）
     * @param chunkIndex    这块在原文里是第几块
     * @param score         相似度分数，越大越像，0~1
     * @param content       块的原文（要喂给大模型当资料）
     */
    public record Hit(long chunkId, String documentTitle, int chunkIndex, double score, String content) {
    }

    /**
     * 检索主方法。
     *
     * @param question 用户的问题，比如"违纪处分有哪几种"
     * @param topK     要返回几个块，一般 3~5
     */
    public List<Hit> search(String question, int topK) {
        long begin = System.currentTimeMillis();

        // ============ 第 1 步：把"问题"也变成向量 ============
        // 库里每个块都是 1024 个数字，问题也必须是 1024 个数字，才能比。
        // 必须用同一个模型（BAAI/bge-m3），否则算出来的数字对不上，结果毫无意义。
        double[] q = embeddingClient.embed(question);

        // ============ 第 2 步：一次读出所有有向量的块 ============
        // 为什么一次全读？189 条数据，1 次查询几十毫秒。
        // 如果在循环里一条条查（N+1 查询），就是 189 次数据库往返，慢几十倍。
        List<DocumentDao.ChunkWithEmbedding> chunks = documentDao.listChunksWithEmbedding();

        // ============ 第 3 步：一个块一个块算分数 ============
        List<Hit> hits = new ArrayList<>(chunks.size());
        for (DocumentDao.ChunkWithEmbedding c : chunks) {

            // 数据库里向量是以 JSON 字符串存的，比如 "[0.0199,0.0422,...]"
            // 先解析成 double[] 才能参与计算
            double[] v = embeddingService.fromJson(c.embedding());

            // 维度校验：万一以后换了模型（比如 768 维），库里还留着旧的 1024 维向量，
            // 不校验就会数组越界，报一个跟你真正问题无关的错误，很难查。
            if (v.length != q.length) {
                throw new IllegalStateException(
                        "维度不一致：问题是 " + q.length + " 维，库里是 " + v.length + " 维。"
                                + "是不是换过 embedding 模型？需要重新向量化。");
            }

            // 点积：对应位置相乘，再全部加起来。
            // 因为两组向量都已经归一化（长度=1），点积就等于余弦相似度，省一次开方。
            double score = 0;
            for (int i = 0; i < q.length; i++) {
                score += q[i] * v[i];
            }

            // 保留 4 位小数，避免返回 0.6317829453294 这种一串
            double rounded = Math.round(score * 10000) / 10000.0;
            hits.add(new Hit(c.chunkId(), c.documentTitle(), c.chunkIndex(), rounded, c.content()));
        }

        // ============ 第 4 步：按分数从高到低排序 ============
        // 用 Comparator 而不是 (a, b) -> (int)(b.score - a.score)：
        // 后者会把小数强制截断成整数（0.63-0.62=0.01 → 变成 0），
        // 分数接近的两块会被认为"一样大"，顺序随机，bug 极其隐蔽。
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());

        // ============ 第 5 步：只取前 topK 个 ============
        // 注意 hits 可能比 topK 少（比如库里只有 2 块，却要 3 个）
        List<Hit> result = hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;

        log.debug("检索完成：问题「{}」，候选 {} 块，返回 {} 块，耗时 {}ms",
                question, chunks.size(), result.size(), System.currentTimeMillis() - begin);
        return result;
    }
}
