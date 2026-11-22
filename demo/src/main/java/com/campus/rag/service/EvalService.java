package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索评测：把题集里的问题逐条喂给各种检索配置，算出 Recall@K 和 MRR。
 *
 * <h3>为什么需要它</h3>
 * 前面所有环节只能回答"能不能跑通"。要回答"好不好"，必须有一组带标准答案的题，
 * 让不同方案在同一把尺子下比。没有评测的"优化"都是拍脑袋——你改了参数，
 * 感觉变好了，其实可能只是恰好碰对了那一两个例子。
 *
 * <h3>两个指标</h3>
 * <ul>
 *   <li><b>Recall@K</b>（召回率）：正确答案有没有进前 K 条。模型只能看到前 K 条，
 *       所以答案不在前 K 条里 = 这一题无论如何都会答错。</li>
 *   <li><b>MRR</b>（平均倒数排名）：正确答案排第几。排第 1 得 1 分，第 2 得 0.5，
 *       第 3 得 0.33。它比 Recall 更严格——能区分"勉强进前 3"和"稳稳第一"。</li>
 * </ul>
 *
 * <h3>怎么判定"命中"</h3>
 * 题集里每题带一组关键词（从原文里摘的真实字串）。检索出的某个块如果<b>包含全部关键词</b>，
 * 就算这一题在这一档命中。这种做法不完美（语义对但用词不同就判错），
 * 但它<b>可复现、不依赖人工打分</b>，适合做方案对比。局限要写进论文里。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final RetrievalService retrievalService;
    private final DocumentDao documentDao;

    /** 题集路径：默认从运行目录的上一级找（IDE 里运行目录是 demo/，题集在仓库根 data/testset/） */
    private final String testsetPath;

    public EvalService(RetrievalService retrievalService,
                       DocumentDao documentDao,
                       @Value("${campus.eval.testset:../data/testset/eval_questions.tsv}") String testsetPath) {
        this.retrievalService = retrievalService;
        this.documentDao = documentDao;
        this.testsetPath = testsetPath;
    }

    /** 一道评测题 */
    public record Question(int id, String text, List<String> keywords, String type, int difficulty) {
    }

    /** 一种题型下的统计 */
    public record TypeStat(int questionCount, int hits, double recall, double mrr) {
    }

    /** 一道没命中的题（rank = 0 表示前 K 条里完全没有正确答案） */
    public record Miss(int questionId, String question, int rank) {
    }

    /** 一种检索配置的成绩 */
    public record VariantStat(String code, String label, int questionCount, int hits,
                              double recall, double mrr, Map<String, TypeStat> byType,
                              List<Miss> misses) {
    }

    /** 整轮评测报告 */
    public record Report(int topK, int chunkTotal, int questionCount, int skippedNoAnswer,
                         long elapsedMs, List<VariantStat> variants) {
    }

    /**
     * 跑一轮评测。
     *
     * @param topK 每次检索取前几条（一般 3，和线上给模型的资料条数一致）
     */
    public Report run(int topK) {
        List<Question> all = loadQuestions();
        // "无答案"题不参与检索评测：它们的正确答案是"拒答"，属于生成层的评估
        List<Question> answerable = new ArrayList<>();
        for (Question q : all) {
            if (!"无答案".equals(q.type())) {
                answerable.add(q);
            }
        }

        List<RetrievalService.Variant> variants = RetrievalService.EVAL_VARIANTS;
        // 每种配置累计：命中数、MRR 分子、逐题首次命中名次
        int[] hitCount = new int[variants.size()];
        double[] mrrSum = new double[variants.size()];
        @SuppressWarnings("unchecked")
        List<List<Integer>> ranksPerVariant = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            ranksPerVariant.add(new ArrayList<>());
        }
        // 题型累计：type -> [题数, 命中数, MRR 分子]（每个配置一份）
        List<Map<String, double[]>> byTypeAcc = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            byTypeAcc.add(new LinkedHashMap<>());
        }

        long begin = System.currentTimeMillis();

        for (Question q : answerable) {
            // 每题只做一次向量化 + 一次读库，4 种配置复用（否则要调用 4 次 embedding 接口）
            Map<String, List<RetrievalService.Hit>> results =
                    retrievalService.searchAllVariants(q.text(), topK);

            for (int v = 0; v < variants.size(); v++) {
                String code = variants.get(v).code();
                List<RetrievalService.Hit> hits = results.get(code);

                int firstRank = 0;
                for (int r = 0; r < hits.size(); r++) {
                    if (containsAll(hits.get(r).content(), q.keywords())) {
                        firstRank = r + 1;
                        break;
                    }
                }

                ranksPerVariant.get(v).add(firstRank);
                if (firstRank > 0) {
                    hitCount[v]++;
                    mrrSum[v] += 1.0 / firstRank;
                }
                Map<String, double[]> acc = byTypeAcc.get(v);
                double[] cell = acc.computeIfAbsent(q.type(), k -> new double[3]);
                cell[0] += 1;
                if (firstRank > 0) {
                    cell[1] += 1;
                    cell[2] += 1.0 / firstRank;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - begin;
        int n = answerable.size();

        List<VariantStat> stats = new ArrayList<>(variants.size());
        for (int v = 0; v < variants.size(); v++) {
            Map<String, TypeStat> byType = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : byTypeAcc.get(v).entrySet()) {
                double[] c = e.getValue();
                int count = (int) c[0];
                int hits = (int) c[1];
                byType.put(e.getKey(), new TypeStat(count, hits,
                        round4(hits / (double) count), round4(c[2] / count)));
            }
            List<Miss> misses = new ArrayList<>();
            List<Integer> ranks = ranksPerVariant.get(v);
            for (int i = 0; i < n; i++) {
                int r = ranks.get(i);
                if (r == 0 || r > topK) {
                    misses.add(new Miss(answerable.get(i).id(), answerable.get(i).text(), r));
                }
            }
            stats.add(new VariantStat(
                    variants.get(v).code(),
                    variants.get(v).label(),
                    n,
                    hitCount[v],
                    round4(hitCount[v] / (double) n),
                    round4(mrrSum[v] / n),
                    byType,
                    misses));
        }

        log.info("评测完成：{} 题 × {} 种配置，耗时 {}ms", n, variants.size(), elapsed);
        return new Report(topK, (int) documentDao.countChunks(), n, all.size() - n, elapsed, stats);
    }

    /** 题集概况（给接口展示用） */
    public Map<String, Object> info() {
        List<Question> all = loadQuestions();
        Map<String, Integer> typeCount = new LinkedHashMap<>();
        for (Question q : all) {
            typeCount.merge(q.type(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", testsetPath);
        m.put("questionCount", all.size());
        m.put("typeCount", typeCount);
        m.put("variants", RetrievalService.EVAL_VARIANTS.stream()
                .map(v -> Map.of("code", v.code(), "label", v.label()))
                .toList());
        return m;
    }

    /**
     * 读题集。
     *
     * <p>格式：id \t 问题 \t 关键词(|分隔) \t 题型 \t 难度；# 开头是注释。
     * 为什么放文件不放数据库？因为题集是"源码"，要跟着代码一起进 Git，
     * 这样才能追溯"这次结果是用哪版题集跑出来的"。
     */
    List<Question> loadQuestions() {
        Path path = Path.of(testsetPath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("找不到评测题集：" + path.toAbsolutePath()
                    + "。检查 campus.eval.testset 配置，或确认工作目录。");
        }
        List<Question> out = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length < 5) {
                    throw new IllegalStateException("题集格式不对（要 5 列，用 TAB 分隔）：" + line);
                }
                List<String> kws = new ArrayList<>();
                for (String k : p[2].split("\\|")) {
                    if (!k.isBlank()) {
                        kws.add(k.trim());
                    }
                }
                out.add(new Question(Integer.parseInt(p[0].trim()), p[1].trim(), kws,
                        p[3].trim(), Integer.parseInt(p[4].trim())));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读题集失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** 块里是否包含全部关键词（关键词组为空时视为不命中，防止配置错误导致"全绿"） */
    private static boolean containsAll(String content, List<String> keywords) {
        if (keywords.isEmpty()) {
            return false;
        }
        for (String k : keywords) {
            if (!content.contains(k)) {
                return false;
            }
        }
        return true;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
