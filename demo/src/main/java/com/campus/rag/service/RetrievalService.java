package com.campus.rag.service;

import com.campus.rag.config.RerankProperties;
import com.campus.rag.service.RerankClient;
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
    /** 精排客户端：拿候选块去问 Cross-Encoder "这条能不能回答问题" */
    private final RerankClient rerankClient;
    private final RerankProperties rerankProperties;

    /** 混合检索里向量这一路的权重（0~1），BM25 那一路是 1 减它 */
    private final double hybridWeightVector;

    /** 没指定方式时用哪种 */
    private final SearchMode defaultMode;

    /**
     * 拒答阈值：精排分低于它就不必再问大模型了（大模型没有可靠资料只能编）。
     *
     * <p>两个阈值分开配，因为两种分数的量纲完全不同：
     * <ul>
     *   <li>精排分（Cross-Encoder 输出）在 0~1，且区分度很大：真能答上来的常见 0.5~0.9，答不上来的 0.0~0.2</li>
     *   <li>混合 RRF 分是 Σ 1/(60+名次)，两路都排第一也只有 0.0328，量级差 20 倍</li>
     * </ul>
     * 用同一个数字卡两种分数，必然有一边是错的。
     */
    private final double minScoreRerank;
    private final double minScoreHybrid;

    /**
     * 自适应融合开关。
     *
     * <p>固定权重 0.2 / 0.8 是在"题面和原文共用汉字"的题上调出来的。
     * 遇到硬改写题（"借的书忘还了怎么算"），BM25 几乎打不出分，
     * 它那 0.8 的权重就成了噪声，反而把向量排对的结果挤下去
     * （实测硬改写题：纯向量 60%、纯 BM25 20%、固定权重混合只有 40%）。
     */
    private final boolean adaptiveWeights;

    /** 判"BM25 到底有没有信号"的门槛：BM25 最高分 ÷ 查询词数 */
    private final double bm25StrongAvg;

    /** BM25 信号弱时，向量权重上调到多少 */
    private final double weakWVector;

    /** BM25 索引缓存：它的构建要遍历所有块，不能每次查询都重建 */
    private volatile Bm25Index bm25Cache;
    private volatile String bm25CacheKey;

    public RetrievalService(EmbeddingClient embeddingClient,
                            DocumentDao documentDao,
                            EmbeddingService embeddingService,
                            RerankClient rerankClient,
                            RerankProperties rerankProperties,
                            @Value("${campus.retrieval.hybrid-w-vector:0.2}") double hybridWeightVector,
                            @Value("${campus.retrieval.mode:hybrid}") String defaultMode,
                            @Value("${campus.retrieval.min-score-rerank:0.0}") double minScoreRerank,
                            @Value("${campus.retrieval.min-score-hybrid:0.0}") double minScoreHybrid,
                            @Value("${campus.retrieval.adaptive:false}") boolean adaptiveWeights,
                            @Value("${campus.retrieval.bm25-strong-avg:1.5}") double bm25StrongAvg,
                            @Value("${campus.retrieval.weak-w-vector:0.7}") double weakWVector) {
        this.embeddingClient = embeddingClient;
        this.documentDao = documentDao;
        this.embeddingService = embeddingService;
        this.rerankClient = rerankClient;
        this.rerankProperties = rerankProperties;
        this.hybridWeightVector = hybridWeightVector;
        this.defaultMode = SearchMode.from(defaultMode);
        this.minScoreRerank = minScoreRerank;
        this.minScoreHybrid = minScoreHybrid;
        this.adaptiveWeights = adaptiveWeights;
        this.bm25StrongAvg = bm25StrongAvg;
        this.weakWVector = weakWVector;
    }

    /**
     * 这一次查询用的融合权重。
     *
     * @param wVector 向量那一路的权重
     * @param wBm25   BM25 那一路的权重（= 1 - wVector）
     * @param signal  "BM25 强度" = BM25 最高分 ÷ 查询词数，越大说明字面越对得上
     * @param reason  人话解释，打日志/调试用
     */
    public record WeightDecision(double wVector, double wBm25, double signal, String reason) {
    }

    /**
     * 决定这次查询的融合权重。
     *
     * <p>为什么用「BM25 最高分 ÷ 查询词数」当信号？
     * BM25 是所有查询词得分之和，词多分自然高，直接拿绝对分数卡阈值会让长问题吃亏；
     * 除以词数就是"平均每个查询词给最高分块贡献了多少分"，才可比。
     */
    WeightDecision decideWeights(Prepared p) {
        int terms = Math.max(1, Tokenizer.tokenize(p.question()).size());
        double top = 0;
        for (double s : p.bm25Scores()) {
            if (s > top) {
                top = s;
            }
        }
        double signal = top / terms;
        if (!adaptiveWeights) {
            return new WeightDecision(hybridWeightVector, 1 - hybridWeightVector, signal, "自适应关闭，用固定权重");
        }
        if (signal < bm25StrongAvg) {
            return new WeightDecision(weakWVector, 1 - weakWVector, signal,
                    "BM25 信号弱（" + round2(signal) + " < " + bm25StrongAvg + "），向量权重上调");
        }
        return new WeightDecision(hybridWeightVector, 1 - hybridWeightVector, signal,
                "BM25 信号足够（" + round2(signal) + " ≥ " + bm25StrongAvg + "），维持默认权重");
    }

    /** 对外暴露一次查询的权重决策（调试/标定用） */
    public WeightDecision explainWeights(String question) {
        return decideWeights(prepare(question));
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
        return search(question, topK, mode, true);
    }

    /**
     * 检索主方法（可关掉相邻块去重）。
     *
     * <p>为什么要把"去重"做成开关？因为评测时要回答"去重到底有没有用"。
     * 写死的逻辑没法对比，只能拍脑袋说"我觉得有用"。
     *
     * @param dedupe true = 跳过同文档相邻块（生产默认）；false = 原样返回
     */
    public List<Hit> search(String question, int topK, SearchMode mode, boolean dedupe) {
        return run(question, topK, mode, dedupe).hits();
    }

    /** 评测里的一种"检索配置"= 检索方式 + 是否去重 + 是否合并相邻块 + 是否展开邻居 + 是否精排 */
    public record Variant(String code, String label, SearchMode mode,
                          boolean dedupe, boolean merge, boolean expand, boolean rerank) {
    }

    /**
     * 评测用的九种配置。
     *
     * <p>前三种是三种检索方式（都开去重，等于线上行为）；
     * 第四种是"混合 + 不去重"，量化去重的收益；
     * 第五种是"混合 + 精排"，量化 Cross-Encoder 的收益；
     * 第六种是"混合 + 合并相邻块"，量化"把邻居拼回来再排名"的收益；
     * 第七种是"合并 + 精排"，量化两者叠加；
     * 第八、九种是"只展开邻居、不改排序"——排序仍用单块（精确），
     * 但交给大模型/前端的内容是展开后的（完整），这是最贴近工程实践的做法。
     */
    public static final List<Variant> EVAL_VARIANTS = List.of(
            new Variant("vector", "纯向量", SearchMode.VECTOR, true, false, false, false),
            new Variant("bm25", "纯 BM25", SearchMode.BM25, true, false, false, false),
            new Variant("hybrid", "混合 RRF", SearchMode.HYBRID, true, false, false, false),
            new Variant("hybrid_no_dedupe", "混合 RRF（不去重）", SearchMode.HYBRID, false, false, false, false),
            new Variant("hybrid_rerank", "混合 + 精排", SearchMode.HYBRID, true, false, false, true),
            new Variant("hybrid_merge", "混合 + 合并相邻块", SearchMode.HYBRID, true, true, false, false),
            new Variant("hybrid_merge_rerank", "合并相邻块 + 精排", SearchMode.HYBRID, true, true, false, true),
            new Variant("hybrid_expand", "混合 + 展开邻居", SearchMode.HYBRID, true, false, true, false),
            new Variant("hybrid_rerank_expand", "精排 + 展开邻居", SearchMode.HYBRID, true, false, true, true)
    );

    /**
     * 一次预处理，跑完所有评测配置。
     *
     * <p>36 题 × 9 种配置如果每题每种各调一次接口，就要向量化 324 次；
     * 这里每题只向量化 1 次、读库 1 次，然后复用分数算 9 种排序
     * （精排那三路另外要多调精排接口，这个省不掉）。
     */
    public Map<String, List<Hit>> searchAllVariants(String question, int topK) {
        Prepared p = prepare(question);
        Map<String, List<Hit>> out = new LinkedHashMap<>();
        for (Variant v : EVAL_VARIANTS) {
            List<Hit> hits;
            if (v.merge()) {
                hits = mergedHits(p, topK, v.dedupe(), v.rerank());
            } else if (v.rerank()) {
                hits = rerankedHits(p, topK, v.dedupe());
            } else {
                hits = buildHits(p, topK, v.mode(), v.dedupe());
            }
            // expand 只替换"内容"，不动排名：让模型/前端看到完整句子
            out.put(v.code(), v.expand() ? expandHits(p, hits) : hits);
        }
        return out;
    }

    /**
     * 「混合粗排 → 精排 → 取前 K」的完整两阶段流程。
     *
     * <p>三步：
     * <ol>
     *   <li>粗排：混合 RRF 先排出候选（默认 10 条，比最终要返回的多，给精排留余地）</li>
     *   <li>精排：把候选逐条和问题一起送给 Cross-Encoder，拿回相关性分数</li>
     *   <li>重排：按精排分数重新排序，取前 K</li>
     * </ol>
     *
     * <p>精排不可用或调用失败时<b>自动降级</b>为粗排结果，不会抛异常。
     */
    private List<Hit> rerankedHits(Prepared p, int topK, boolean dedupe) {
        int candidates = Math.max(topK, rerankProperties.getCandidates());
        List<Hit> pool = buildHits(p, candidates, SearchMode.HYBRID, dedupe);
        if (pool.isEmpty()) {
            return pool;
        }
        if (!rerankClient.available()) {
            log.debug("精排未启用，直接用混合结果");
            return pool.size() <= topK ? pool : pool.subList(0, topK);
        }

        List<String> documents = new ArrayList<>(pool.size());
        for (Hit h : pool) {
            documents.add(rerankClient.truncate(h.content()));
        }
        double[] scores = rerankClient.scores(p.question(), documents);
        if (scores == null) {
            // 降级：保持粗排顺序，只截到 topK
            return pool.size() <= topK ? pool : pool.subList(0, topK);
        }

        List<Integer> order = RerankClient.orderByScoreDesc(scores);
        List<Hit> out = new ArrayList<>(topK);
        for (int rank = 0; rank < order.size() && out.size() < topK; rank++) {
            Hit h = pool.get(order.get(rank));
            out.add(new Hit(h.chunkId(), h.documentTitle(), h.chunkIndex(),
                    Math.round(scores[order.get(rank)] * 10000) / 10000.0, h.content()));
        }
        return out;
    }

    /**
     * 「混合粗排 → 合并相邻块 →（可选）精排 → 取前 K」。
     *
     * <p><b>为什么要把相邻块拼回来</b>：切分是机械的，一条规定很可能被切成两块——
     * 比如《违纪处分条例》里「给予下列之一的处分：（1）警告……（5）开除学籍」，
     * 切点正好落在冒号后面，于是「有哪几种处分」这个答案被劈成两半：
     * 前一块只有"给予下列之一的处"，后一块才是清单。
     * 单看任何一块都不完整，粗排和精排都容易判低。
     *
     * <p>做法：对每个候选块，把「前一块 + 自己 + 后一块」拼成一段更长的文本再送去排序。
     * 这样模型能看到完整句子，评分自然更准。
     *
     * <p><b>代价</b>：
     * <ul>
     *   <li>送给模型的文本变长（约 3 倍），精排会慢一点</li>
     *   <li>相邻块 overlap 有 50 字重复，拼起来会有冗余（已在下文注释说明为什么不特意去重）</li>
     *   <li>两个候选块如果本来就是邻居，合并后内容会重叠 → 用「已占用的块区间」把后来的跳过</li>
     * </ul>
     */
    private List<Hit> mergedHits(Prepared p, int topK, boolean dedupe, boolean withRerank) {
        // 候选要比最终答案多，才有"合并"和"重排"的余地
        int candidates = Math.max(topK + 2, rerankProperties.getCandidates());
        List<Hit> pool = buildHits(p, candidates, SearchMode.HYBRID, dedupe);
        if (pool.isEmpty()) {
            return pool;
        }

        Map<String, Map<Integer, String>> contents = indexContents(p.chunks());
        List<Hit> merged = new ArrayList<>(pool.size());
        // 每份文档已经"占用"的块区间，用来避免两个候选合并出重复内容
        Map<String, List<int[]>> occupied = new LinkedHashMap<>();

        for (Hit h : pool) {
            int from = h.chunkIndex() - 1;
            int to = h.chunkIndex() + 1;
            List<int[]> ranges = occupied.computeIfAbsent(h.documentTitle(), k -> new ArrayList<>());
            boolean overlaps = false;
            for (int[] r : ranges) {
                if (!(to < r[0] || from > r[1])) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                log.debug("合并相邻块：跳过 {} #{}（与已选内容重叠）", h.documentTitle(), h.chunkIndex());
                continue;
            }
            ranges.add(new int[]{from, to});
            merged.add(new Hit(h.chunkId(), h.documentTitle(), h.chunkIndex(), h.score(),
                    joinNeighbors(contents.get(h.documentTitle()), h.chunkIndex())));
        }
        if (merged.isEmpty()) {
            return merged;
        }

        if (!withRerank || !rerankClient.available()) {
            return merged.size() <= topK ? merged : merged.subList(0, topK);
        }

        List<String> documents = new ArrayList<>(merged.size());
        for (Hit h : merged) {
            documents.add(rerankClient.truncate(h.content()));
        }
        double[] scores = rerankClient.scores(p.question(), documents);
        if (scores == null) {
            return merged.size() <= topK ? merged : merged.subList(0, topK);
        }

        List<Integer> order = RerankClient.orderByScoreDesc(scores);
        List<Hit> out = new ArrayList<>(topK);
        for (int rank = 0; rank < order.size() && out.size() < topK; rank++) {
            Hit h = merged.get(order.get(rank));
            out.add(new Hit(h.chunkId(), h.documentTitle(), h.chunkIndex(),
                    Math.round(scores[order.get(rank)] * 10000) / 10000.0, h.content()));
        }
        return out;
    }

    /**
     * 只把内容换成"展开邻居后的文本"，排名与分数完全不动。
     *
     * <p>这是与 {@link #mergedHits} 的关键区别：
     * <ul>
     *   <li>合并（merge）：先用"前中后三块"去排名 → 排序质量受影响（可能变好也可能变坏）</li>
     *   <li>展开（expand）：先用单块排名（精确），再把内容补全 → 排序不变，但大模型看到的更完整</li>
     * </ul>
     * 工程上通常选后者：排序要的是"精"，给模型看要的是"全"。
     */
    private List<Hit> expandHits(Prepared p, List<Hit> hits) {
        if (hits.isEmpty()) {
            return hits;
        }
        Map<String, Map<Integer, String>> contents = indexContents(p.chunks());
        List<Hit> out = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            out.add(new Hit(h.chunkId(), h.documentTitle(), h.chunkIndex(), h.score(),
                    joinNeighbors(contents.get(h.documentTitle()), h.chunkIndex())));
        }
        return out;
    }

    /** 建索引：文档标题 → (块序号 → 块正文)，用于快速取邻居 */
    private Map<String, Map<Integer, String>> indexContents(List<DocumentDao.ChunkWithEmbedding> chunks) {        Map<String, Map<Integer, String>> out = new LinkedHashMap<>();
        for (DocumentDao.ChunkWithEmbedding c : chunks) {
            out.computeIfAbsent(c.documentTitle(), k -> new LinkedHashMap<>()).put(c.chunkIndex(), c.content());
        }
        return out;
    }

    /**
     * 把前一块 + 自己 + 后一块拼起来。
     *
     * <p>为什么不在这里做"删掉重叠的 50 字"？因为块边界是按句号回退的，
     * 重叠处并不总是恰好 50 字，机械删会切断句子。让模型看到少量重复，
     * 比切断半句话的伤害小。
     */
    private String joinNeighbors(Map<Integer, String> byIndex, int index) {
        if (byIndex == null || byIndex.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = index - 1; i <= index + 1; i++) {
            String text = byIndex.get(i);
            if (text == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                // 邻居块的块头前缀（【标题 · 章节】）去掉：拼三段时留三个标题会把正文冲淡
                // 自己的那块保留前缀（它是"这是哪份文件哪一章"的唯一线索）
                text = CHUNK_PREFIX.matcher(text).replaceFirst("");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /** 块头前缀，形如「【文档标题 · 第X章 章节名】」，由 ChunkEnricher 生成 */
    private static final java.util.regex.Pattern CHUNK_PREFIX = java.util.regex.Pattern.compile("^【[^】]*】\\s*");

    /** 生产用入口：合并相邻块（可选精排），给搜索接口调 */
    public List<Hit> searchMerged(String question, int topK, boolean withRerank) {
        return mergedHits(prepare(question), topK, true, withRerank);
    }

    /**
     * 三路同时跑一遍，返回三种方式各自的结果（调效果用，也方便你直接对比）。
     *
     * <p>为什么三个一起做？因为向量化和读库都只做一次，
     * 如果外面调用三次接口，就是要付三次 embedding 的钱和三次读库的时间。
     */
    public List<ModeResult> compare(String question, int topK) {
        Prepared p = prepare(question);
        List<ModeResult> out = new ArrayList<>(5);
        for (SearchMode mode : SearchMode.values()) {
            out.add(new ModeResult(mode.code(), mode.label(), buildHits(p, topK, mode)));
        }
        // 第四路：混合 + 精排（精排未启用时这里等价于混合结果）
        out.add(new ModeResult("hybrid_rerank", "混合 + 精排", rerankedHits(p, topK, true)));
        // 第五路：精排 + 展开邻居 = 线上推荐方案（排序用单块，内容用三块）
        out.add(new ModeResult("hybrid_rerank_expand", "精排 + 展开邻居", searchBest(question, topK)));
        return out;
    }

    /**
     * 线上推荐方案：混合粗排 → 精排 → 展开邻居。
     *
     * <p>评测结论（32 道有效题，docs/08）：
     * <ul>
     *   <li>只精排：Recall@3 0.9688 / MRR 0.9063</li>
     *   <li>只展开邻居：0.9375 / 0.9219</li>
     *   <li><b>精排 + 展开邻居：0.9688 / 0.9375（两个指标同时最好）</b></li>
     *   <li>先合并再精排：0.9063 / 0.8854 —— 明显更差，因为三块拼起来会把无关内容也喂给精排</li>
     * </ul>
     * 所以正确的顺序是「排序要精，给模型看要全」：排名用单块，内容用三块。
     */
    public List<Hit> searchBest(String question, int topK) {
        return searchBestGated(question, topK).hits();
    }

    /**
     * 推荐的检索结果 + 「这道题到底该不该答」的判断。
     *
     * @param hits      检索到的块（内容已展开邻居）
     * @param topScore  最高分（精排开着就是精排分，没开就是混合 RRF 分）
     * @param reranked  这次有没有真的用到精排
     * @param threshold 本次生效的阈值（0 表示没设阈值）
     * @param gated     是否因分数太低而建议拒答
     */
    public record Best(List<Hit> hits, double topScore, boolean reranked,
                       double threshold, boolean gated) {
    }

    /**
     * 带"分数闸门"的检索。
     *
     * <p><b>为什么要在检索层拦</b>：没有相关资料的题，光靠提示词让模型拒绝是"兜底"，
     * 兜得住也白花一次大模型调用。加了闸门就是"资料不够 → 根本不问模型"，
     * 既省钱又少一次编造的机会。这叫"宁可说没查到，也不猜"。
     */
    public Best searchBestGated(String question, int topK) {
        Prepared p = prepare(question);
        boolean reranked = rerankClient.available();
        List<Hit> ranked = reranked
                ? rerankedHits(p, topK, true)
                : buildHits(p, topK, SearchMode.HYBRID, true);
        List<Hit> hits = expandHits(p, ranked);
        double topScore = hits.isEmpty() ? 0.0 : hits.get(0).score();
        double threshold = reranked ? minScoreRerank : minScoreHybrid;
        boolean gated = threshold > 0 && topScore < threshold;
        if (gated) {
            log.info("检索置信度不足，建议拒答：{}（最高分 {} < 阈值 {}）", question, topScore, threshold);
        }
        return new Best(hits, topScore, reranked, threshold, gated);
    }

    /** 直接走"混合 + 精排"两阶段（给搜索接口用） */
    public List<Hit> searchReranked(String question, int topK) {
        return rerankedHits(prepare(question), topK, true);
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
        return buildHits(p, topK, mode, true);
    }

    /** 按指定方式 + 是否去重 把分数变成排好序的 Hit 列表 */
    private List<Hit> buildHits(Prepared p, int topK, SearchMode mode, boolean dedupe) {
        List<DocumentDao.ChunkWithEmbedding> chunks = p.chunks();
        if (chunks.isEmpty()) {
            return List.of();
        }

        double[] scores;
        switch (mode) {
            case VECTOR -> scores = p.vectorScores();
            case BM25 -> scores = p.bm25Scores();
            case HYBRID -> {
                WeightDecision d = decideWeights(p);
                scores = rrf(p.vectorScores(), p.bm25Scores(), d.wVector());
            }
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
            if (dedupe) {
                for (Hit kept : hits) {
                    boolean sameDoc = kept.documentTitle().equals(c.documentTitle());
                    boolean adjacent = Math.abs(kept.chunkIndex() - c.chunkIndex()) <= 1;
                    if (sameDoc && adjacent) {
                        isDuplicate = true;
                        break;
                    }
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
        return rrf(vectorScores, bm25Scores, hybridWeightVector);
    }

    /** RRF 融合（指定向量权重）：自适应模式下权重由 {@link #decideWeights} 算出 */
    private double[] rrf(double[] vectorScores, double[] bm25Scores, double wVector) {
        int n = vectorScores.length;
        int[] vRank = ranksOf(vectorScores);
        int[] bRank = ranksOf(bm25Scores);
        double wv = wVector;
        double wb = 1 - wv;
        double[] fused = new double[n];
        for (int i = 0; i < n; i++) {
            fused[i] += wv / (RRF_K + vRank[i] + 1);
            fused[i] += wb / (RRF_K + bRank[i] + 1);
        }
        return fused;
    }

    /** 保留两位小数，只用于日志/调试文案 */
    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
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

    private Run run(String question, int topK, SearchMode mode, boolean dedupe) {
        long begin = System.currentTimeMillis();
        Prepared p = prepare(question);
        List<Hit> hits = buildHits(p, topK, mode, dedupe);
        long elapsed = System.currentTimeMillis() - begin;
        log.debug("检索完成：问题「{}」，方式 {}，去重 {}，候选 {} 块，返回 {} 块，耗时 {}ms",
                question, mode.code(), dedupe, p.chunks().size(), hits.size(), elapsed);
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
        m.put("adaptive", adaptiveWeights);
        m.put("bm25StrongAvg", bm25StrongAvg);
        m.put("weakWVector", weakWVector);
        return m;
    }
}
