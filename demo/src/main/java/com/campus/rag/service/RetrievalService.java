package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务：输入一个问题，返回库里最像的 topK 个文本块。
 *
 * <p>这是 RAG 里 "R"（Retrieval，检索）那一步。
 * 后面的问答生成，就是把这几个块塞给大模型当资料。
 *
 * <h3>三种检索方式</h3>
 * <ul>
 *   <li><b>vector</b> 纯向量：懂"意思相近"，但列举型问题（"有哪几种"）容易漏</li>
 *   <li><b>bm25</b> 纯字面：只认字面出现，列举型问题很强，但换个说法就找不到</li>
 *   <li><b>hybrid</b> 混合：两路各排一次名，用 RRF 融合（默认）</li>
 * </ul>
 *
 * <h3>为什么混合能赢</h3>
 * 两路的"错误"不重叠：向量漏掉的块，BM25 往往排得很高；BM25 漏掉的，向量往往能捞回来。
 * RRF 只用一个块的<b>名次</b>做融合，不需要把两种分数拉到同一个量纲上比较——
 * 余弦是 0~1，BM25 是 0~30，直接相加等于让 BM25 一家说了算。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * RRF 公式里的 k：score = Σ 权重 / (k + 名次)
     * 取 60 是业界惯例（来自 2009 年那篇 RRF 论文），作用是"把名次差距压平"：
     * 第 1 名和第 2 名差 1/61 vs 1/62，差距很小 → 不会因为某一路的第一名而完全决定结果。
     */
    private static final int RRF_K = 60;

    private final EmbeddingClient embeddingClient;
    private final DocumentDao documentDao;
    private final EmbeddingService embeddingService;

    /** 混合检索里向量这一路的权重（0~1），BM25 那一路是 1 减它 */
    private final double hybridWeightVector;

    /** 没指定方式时用哪种 */
    private final SearchMode defaultMode;

    /** BM25 索引缓存：它的构建要遍历所有块，不能每次查询都重建 */
    private volatile Bm25Index bm25Cache;
    private volatile String bm25CacheKey;

    public RetrievalService(EmbeddingClient embeddingClient,
                            DocumentDao documentDao,
                            EmbeddingService embeddingService,
                            @Value("${campus.retrieval.hybrid-w-vector:0.2}") double hybridWeightVector,
                            @Value("${campus.retrieval.mode:hybrid}") String defaultMode) {
        this.embeddingClient = embeddingClient;
        this.documentDao = documentDao;
        this.embeddingService = embeddingService;
        this.hybridWeightVector = hybridWeightVector;
        this.defaultMode = SearchMode.from(defaultMode);
    }

    /** 检索方式 */
    public enum SearchMode {
        VECTOR("vector", "纯向量"),
        BM25("bm25", "纯字面 BM25"),
        HYBRID("hybrid", "混合 RRF");

        private final String code;
        private final String label;

        SearchMode(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }

        /** 把外部传进来的字符串转成枚举，认不出就退回 HYBRID（不抛异常，避免接口 500） */
        public static SearchMode from(String raw) {
            if (raw != null) {
                for (SearchMode m : values()) {
                    if (m.code.equalsIgnoreCase(raw.trim())) {
                        return m;
                    }
                }
            }
            return HYBRID;
        }
    }

    /**
     * 一条检索结果。
     *
     * @param chunkId       块 id（以后做"引用溯源"要用它）
     * @param documentTitle 出自哪个文档（用户要看得出处）
     * @param chunkIndex    这块在原文里是第几块
     * @param score         分数，越大越像。注意：向量是 0~1 的余弦，BM25 是无上限的正数，混合是 RRF 分（约 0.016 量级）——<b>三种方式的分数不能互相比较</b>
     * @param content       块的原文（要喂给大模型当资料）
     */
    public record Hit(long chunkId, String documentTitle, int chunkIndex, double score, String content) {
    }

    /** 一种检索方式的结果（给"三路对比"接口用） */
    public record ModeResult(String mode, String label, List<Hit> hits) {
    }

    /** 检索主方法：用配置里的默认方式 */
    public List<Hit> search(String question, int topK) {
        return search(question, topK, defaultMode);
    }

    /**
     * 检索主方法。
     *
     * @param question 用户的问题，比如"违纪处分有哪几种"
     * @param topK     要返回几个块，一般 3~5
     * @param mode     用哪种检索方式
     */
    public List<Hit> search(String question, int topK, SearchMode mode) {
        return run(question, topK, mode).hits();
    }

    /**
     * 三路同时跑一遍，返回三种方式各自的结果（调效果用，也方便你直接对比）。
     *
     * <p>为什么三个一起做？因为向量化和读库都只做一次，
     * 如果外面调用三次接口，就是要付三次 embedding 的钱和三次读库的时间。
     */
    public List<ModeResult> compare(String question, int topK) {
        Prepared p = prepare(question);
        List<ModeResult> out = new ArrayList<>(3);
        for (SearchMode mode : SearchMode.values()) {
            out.add(new ModeResult(mode.code(), mode.label(), buildHits(p, topK, mode)));
        }
        return out;
    }

    /** 内部统一返回值：一次 prepare，多种方式复用 */
    private record Prepared(String question,
                            List<DocumentDao.ChunkWithEmbedding> chunks,
                            double[] vectorScores,
                            double[] bm25Scores,
                            long prepareMs) {
    }

    private record Run(List<Hit> hits, long elapsedMs) {
    }

    /**
     * 预处理：向量化问题 + 读库 + 算两路分数。
     * 三种检索方式共用这一次的结果。
     */
    private Prepared prepare(String question) {
        long begin = System.currentTimeMillis();

        // 第 1 步：把"问题"也变成向量（1024 个数）
        // 必须用同一个模型，否则数字对不上，结果毫无意义。
        double[] q = embeddingClient.embed(question);

        // 第 2 步：一次读出所有有向量的块
        // 为什么一次全读？414 条数据，1 次查询几十毫秒。
        // 在循环里一条条查（N+1 查询）就是 414 次数据库往返，慢几十倍。
        List<DocumentDao.ChunkWithEmbedding> chunks = documentDao.listChunksWithEmbedding();

        // 第 3 步：算向量分（点积 = 余弦，因为都已归一化）
        double[] vectorScores = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            DocumentDao.ChunkWithEmbedding c = chunks.get(i);
            double[] v = embeddingService.fromJson(c.embedding());
            if (v.length != q.length) {
                throw new IllegalStateException(
                        "维度不一致：问题是 " + q.length + " 维，库里是 " + v.length + " 维。"
                                + "是不是换过 embedding 模型？需要重新向量化。");
            }
            double score = 0;
            for (int k = 0; k < q.length; k++) {
                score += q[k] * v[k];
            }
            vectorScores[i] = score;
        }

        // 第 4 步：算 BM25 分（索引按指纹缓存，变了才重建）
        Bm25Index index = bm25Index(chunks);
        double[] bm25Scores = index.scores(question);

        return new Prepared(question, chunks, vectorScores, bm25Scores,
                System.currentTimeMillis() - begin);
    }

    /** 取 BM25 索引：块数或最大块 id 变了就重建 */
    private Bm25Index bm25Index(List<DocumentDao.ChunkWithEmbedding> chunks) {
        Bm25Index cached = bm25Cache;
        String key = cached == null ? "" : cached.fingerprint();
        // 先用现成索引算一下指纹（便宜），一致就直接复用
        if (cached != null && chunksAreSame(cached, chunks)) {
            return cached;
        }
        long t0 = System.currentTimeMillis();
        Bm25Index built = Bm25Index.build(chunks);
        bm25Cache = built;
        bm25CacheKey = built.fingerprint();
        log.info("BM25 索引重建：{} 块，耗时 {}ms（旧指纹 {}）",
                built.size(), System.currentTimeMillis() - t0, key);
        return built;
    }

    /** 比对索引和当前数据是否同一批（看块数 + 最大块 id） */
    private boolean chunksAreSame(Bm25Index index, List<DocumentDao.ChunkWithEmbedding> chunks) {
        if (index.size() != chunks.size()) {
            return false;
        }
        long maxId = 0;
        for (DocumentDao.ChunkWithEmbedding c : chunks) {
            maxId = Math.max(maxId, c.chunkId());
        }
        return index.fingerprint().endsWith(":" + maxId) && bm25CacheKey != null
                && bm25CacheKey.equals(index.fingerprint());
    }

    /** 按指定方式把分数变成排好序的 Hit 列表 */
    private List<Hit> buildHits(Prepared p, int topK, SearchMode mode) {
        List<DocumentDao.ChunkWithEmbedding> chunks = p.chunks();
        if (chunks.isEmpty()) {
            return List.of();
        }

        double[] scores;
        switch (mode) {
            case VECTOR -> scores = p.vectorScores();
            case BM25 -> scores = p.bm25Scores();
            case HYBRID -> scores = rrf(p.vectorScores(), p.bm25Scores());
            default -> scores = p.vectorScores();
        }

        // 排序：分数降序 + 同分按块 id 升序（保证结果确定，可复现）
        Integer[] order = new Integer[chunks.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator
                .comparingDouble((Integer i) -> scores[i]).reversed()
                .thenComparingLong(i -> chunks.get(i).chunkId()));

        // ============ 相邻块去重 ============
        // 背景：切分时相邻块有 50 字重叠（overlap），所以同一句话会同时出现在
        // 第 N 块的尾巴和第 N+1 块的开头。检索时这两块的向量/字面都很像，
        // 于是 Top3 里经常有两个块在说同一件事 —— 白占一个引用位。
        //
        // 做法：从最好的块往下走，遇到"同文档 + 位置相邻"的块就跳过，
        // 直到凑够 topK 个"真正不同"的块。
        //
        // 为什么只判相邻（<=1）而不是"同文档就去重"？
        // 因为同一份规定里的第 3 块和第 20 块讲的是完全不同的内容，
        // 它们都该出现在结果里（比如"申请条件"和"办理流程"）。
        List<Hit> hits = new ArrayList<>(topK);
        int skipped = 0;
        for (int r = 0; r < order.length; r++) {
            if (hits.size() >= topK) {
                break;
            }
            int i = order[r];
            DocumentDao.ChunkWithEmbedding c = chunks.get(i);

            boolean isDuplicate = false;
            for (Hit kept : hits) {
                boolean sameDoc = kept.documentTitle().equals(c.documentTitle());
                boolean adjacent = Math.abs(kept.chunkIndex() - c.chunkIndex()) <= 1;
                if (sameDoc && adjacent) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) {
                skipped++;
                log.debug("跳过相邻重复块：{} #{}（分数 {}）",
                        c.documentTitle(), c.chunkIndex(), Math.round(scores[i] * 10000) / 10000.0);
                continue;
            }

            hits.add(new Hit(c.chunkId(), c.documentTitle(), c.chunkIndex(),
                    Math.round(scores[i] * 10000) / 10000.0, c.content()));
        }
        if (skipped > 0) {
            log.debug("相邻块去重：跳过 {} 块，返回 {} 块", skipped, hits.size());
        }
        return hits;
    }

    /**
     * RRF 融合：两路各排一次名，按名次给分。
     *
     * <pre>
     * fused[块] = wVector / (60 + 向量名次) + wBm25 / (60 + BM25名次)
     * </pre>
     *
     * <p>为什么用名次而不是分数？因为两种分数没法直接比：
     * 余弦最大 1，BM25 可能 30+。直接相加等于"BM25 说了算"。
     */
    private double[] rrf(double[] vectorScores, double[] bm25Scores) {
        int n = vectorScores.length;
        int[] vRank = ranksOf(vectorScores);
        int[] bRank = ranksOf(bm25Scores);
        double wv = hybridWeightVector;
        double wb = 1 - wv;
        double[] fused = new double[n];
        for (int i = 0; i < n; i++) {
            fused[i] += wv / (RRF_K + vRank[i] + 1);
            fused[i] += wb / (RRF_K + bRank[i] + 1);
        }
        return fused;
    }

    /** 把分数数组转成"名次数组"：ranks[块下标] = 该块排第几（从 0 开始） */
    private int[] ranksOf(double[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator
                .comparingDouble((Integer i) -> scores[i]).reversed()
                .thenComparingInt(i -> i));
        int[] ranks = new int[scores.length];
        for (int r = 0; r < order.length; r++) {
            ranks[order[r]] = r;
        }
        return ranks;
    }

    private Run run(String question, int topK, SearchMode mode) {
        long begin = System.currentTimeMillis();
        Prepared p = prepare(question);
        List<Hit> hits = buildHits(p, topK, mode);
        long elapsed = System.currentTimeMillis() - begin;
        log.debug("检索完成：问题「{}」，方式 {}，候选 {} 块，返回 {} 块，耗时 {}ms",
                question, mode.code(), p.chunks().size(), hits.size(), elapsed);
        return new Run(hits, elapsed);
    }

    /** 当前默认检索方式（给接口返回，前端要显示） */
    public String defaultModeCode() {
        return defaultMode.code();
    }

    /** 当前混合权重（给接口返回） */
    public double hybridWeightVector() {
        return hybridWeightVector;
    }

    /** 调试：当前 BM25 索引的规模 */
    public Map<String, Object> bm25Stats() {
        Bm25Index index = bm25Cache;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cached", index != null);
        m.put("size", index == null ? 0 : index.size());
        m.put("fingerprint", index == null ? null : index.fingerprint());
        m.put("defaultMode", defaultMode.code());
        m.put("hybridWVector", hybridWeightVector);
        return m;
    }
}
