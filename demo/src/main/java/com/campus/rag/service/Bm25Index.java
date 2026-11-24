package com.campus.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 索引：按"字面命中"给文本块打分。
 *
 * <p><b>它和我们已有的向量检索是互补的</b>
 * <ul>
 *   <li>向量：懂"意思相近"（「宿舍」≈「公寓」），但**字面不一样的列举型问题会漏**</li>
 *   <li>BM25：只认"字面出现"，所以问「违纪处分有哪几种」时，
 *       含"警告、严重警告、记过、留校察看、开除学籍"的那块分数会飙高</li>
 * </ul>
 *
 * <p><b>BM25 公式（三个直觉）</b>
 * <pre>
 * 分数 = Σ 每个查询词： IDF × (词频 × (K1+1)) / (词频 + K1 × (1 - B + B × 块长/平均块长))
 *
 * 直觉 1：IDF（逆文档频率）= 一个词越"稀有"越值钱。
 *        "学生"几乎每块都有 → IDF 低；"留校察看"只有几块有 → IDF 高。
 * 直觉 2：词频（TF）饱和 = 一个词出现 5 次比 1 次重要，但不会重要 5 倍。
 *        所以分子分母都有词频，自然做了"饱和"。
 * 直觉 3：长度归一化 = 长块天然容易命中更多词，要按长度折价（B 控制折价强度）。
 * </pre>
 */
public class Bm25Index {

    /** K1：词频饱和速度。1.2~2.0 是通用区间，取 1.5 */
    private static final double K1 = 1.5;

    /** B：长度归一化强度。0=不归一化，1=完全归一化，通用值 0.75 */
    private static final double B = 0.75;

    /** 原始块列表，下标即"第几块"，分数数组和它一一对应 */
    private final List<DocumentDao.ChunkWithEmbedding> chunks;

    /** 每块：词 → 出现次数。termFreqs[块下标] = {词: tf} */
    private final List<Map<String, Integer>> termFreqs;

    /** 每个词出现在多少个块里（document frequency） */
    private final Map<String, Integer> docFreq;

    /** 每块的长度（token 数） */
    private final int[] lengths;

    /** 平均块长，长度归一化的分母 */
    private final double avgLength;

    private Bm25Index(List<DocumentDao.ChunkWithEmbedding> chunks,
                      List<Map<String, Integer>> termFreqs,
                      Map<String, Integer> docFreq,
                      int[] lengths,
                      double avgLength) {
        this.chunks = chunks;
        this.termFreqs = termFreqs;
        this.docFreq = docFreq;
        this.lengths = lengths;
        this.avgLength = avgLength;
    }

    /**
     * 建索引：一次遍历所有块，统计"每个块里每个词出现几次"。
     *
     * <p>复杂度 O(总词数)，414 个块大约 2 万个 token，建一次几毫秒。
     * 所以不要在每次查询时重建——见 RetrievalService 里的缓存。
     */
    public static Bm25Index build(List<DocumentDao.ChunkWithEmbedding> chunks) {//建索引：一次扫全库，记下"每块里每个词出现几次"
        int n = chunks.size();
        List<Map<String, Integer>> termFreqs = new ArrayList<>(n);
        Map<String, Integer> docFreq = new HashMap<>();
        int[] lengths = new int[n];
        long totalLength = 0;

        for (int i = 0; i < n; i++) {
            List<String> tokens = Tokenizer.tokenize(chunks.get(i).content());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            termFreqs.add(tf);
            lengths[i] = tokens.size();
            totalLength += tokens.size();
            // 这个词出现在这一块里 → df +1（注意：同一块里出现多次只算一次）
            for (String t : tf.keySet()) {
                docFreq.merge(t, 1, Integer::sum);
            }
        }

        double avgLength = n == 0 ? 0 : (double) totalLength / n;
        return new Bm25Index(chunks, termFreqs, docFreq, lengths, avgLength);
    }

    public int size() {
        return chunks.size();
    }

    /**
     * 缓存指纹：块数 + 最大块 id。
     * 只要这两样没变，索引就可以复用；一变（上传/删除文档）就重建。
     */
    public String fingerprint() {//fingerprint()（缓存指纹）
        long maxId = 0;
        for (DocumentDao.ChunkWithEmbedding c : chunks) {
            maxId = Math.max(maxId, c.chunkId());
        }
        return chunks.size() + ":" + maxId;
    }

    /**
     * 给每个块打一个 BM25 分。
     *
     * @param query 用户问题
     * @return 长度 = 块数的分数数组，下标和第块一一对应
     */
    public double[] scores(String query) {//打分：给每个块算一个分数，返回 double[]
        // 1) 用 Set 而不是 List：问题也分词，并去重：同一个词问两遍不该算两遍分
        Set<String> queryTerms = new LinkedHashSet<>(Tokenizer.tokenize(query));
        double[] scores = new double[chunks.size()];
        if (queryTerms.isEmpty() || chunks.isEmpty()) {
            return scores;
        }

        int totalDocs = chunks.size();

        // 2) 算每个查询词的 IDF，只算一次
        Map<String, Double> idfCache = new HashMap<>();
        for (String term : queryTerms) {
            int df = docFreq.getOrDefault(term, 0);
            if (df == 0) {
                // 全库都没有这个词 → 它对打分没有任何贡献，跳过
                // 注意：这里不能"给个惩罚分"，因为那会让所有块都掉分，改变不了排序
                continue;
            }
            // 教科书公式：idf = ln(1 + (N - df + 0.5) / (df + 0.5))
            // 加 1 是为了让 idf 恒为正（df = N 时也不会变成 0 或负数）
            double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
            idfCache.put(term, idf);
        }
        if (idfCache.isEmpty()) {
            return scores;
        }

        // 3) 逐块算分
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            double lenNorm = avgLength == 0 ? 1.0 : lengths[i] / avgLength;
            double sum = 0;
            for (Map.Entry<String, Double> e : idfCache.entrySet()) {
                int freq = tf.getOrDefault(e.getKey(), 0);
                if (freq == 0) {
                    continue;
                }
                double idf = e.getValue();
                // 词频饱和 + 长度归一化
                sum += idf * (freq * (K1 + 1)) / (freq + K1 * (1 - B + B * lenNorm));
            }
            scores[i] = sum;
        }
        return scores;
    }

    /**
     * 顺手提供一个"查询词被覆盖了多少"的指标（IDF 加权）。
     * 以后做重排时可以直接用：正确块通常覆盖了问题里更多的高 IDF 词。
     */
    public double idfCoverage(String query, int chunkIndex) {//idfCoverage()（覆盖度信号）
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
            return 0;
        }
        Set<String> queryTerms = new LinkedHashSet<>(Tokenizer.tokenize(query));
        Map<String, Integer> tf = termFreqs.get(chunkIndex);
        double total = 0;
        double hit = 0;
        for (String term : queryTerms) {
            int df = docFreq.getOrDefault(term, 0);
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
            total += idf;
            if (tf.containsKey(term)) {
                hit += idf;
            }
        }
        return total == 0 ? 0 : hit / total;
    }

    /** 调试用：看某个词在几块里出现过 */
    public int documentFrequency(String term) {
        return docFreq.getOrDefault(Tokenizer.clean(term), 0);
    }

    /** 调试用：看某个块的 token 数 */
    public int lengthOf(int chunkIndex) {
        return chunkIndex >= 0 && chunkIndex < lengths.length ? lengths[chunkIndex] : -1;
    }

    /** 调试用：看块里出现过的词（前 limit 个） */
    public Set<String> vocabOf(int chunkIndex, int limit) {
        Set<String> out = new HashSet<>();
        if (chunkIndex < 0 || chunkIndex >= termFreqs.size()) {
            return out;
        }
        for (String t : termFreqs.get(chunkIndex).keySet()) {
            out.add(t);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
