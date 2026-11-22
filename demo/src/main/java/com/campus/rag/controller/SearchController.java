package com.campus.rag.controller;

import com.campus.rag.service.QueryRewriter;
import com.campus.rag.service.RetrievalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索接口：给前端和调试用的"只检索、不生成回答"入口。
 *
 * <p>为什么单独开一个？因为调模型回答要花钱、要等几秒，
 * 而检索本身很快。调效果时先看检索对不对，比看最终回答错在哪更好定位问题。
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final RetrievalService retrievalService;
    private final QueryRewriter queryRewriter;

    public SearchController(RetrievalService retrievalService, QueryRewriter queryRewriter) {
        this.retrievalService = retrievalService;
        this.queryRewriter = queryRewriter;
    }

    /**
     * 例子：GET /api/search?q=违纪处分有哪几种&topK=3&mode=hybrid
     *
     * @param mode vector / bm25 / hybrid / <b>best</b>（best = 混合 → 精排 → 展开邻居，
     *             并返回 gated/topScore，用于观察"该不该拒答"）
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(defaultValue = "3") int topK,
                                      @RequestParam(required = false) String mode) {
        long begin = System.currentTimeMillis();
        int k = Math.max(1, topK);

        // best 是"生产线"，它多返回两个字段（最高分、是否被闸门拦下）
        if ("best".equalsIgnoreCase(mode)) {
            RetrievalService.Best best = retrievalService.searchBestGated(q, k);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("question", q);
            data.put("mode", "best");
            data.put("modeLabel", "混合 + 精排 + 展开邻居");
            data.put("topK", k);
            data.put("count", best.hits().size());
            data.put("topScore", best.topScore());
            data.put("reranked", best.reranked());
            data.put("threshold", best.threshold());
            data.put("gated", best.gated());
            data.put("elapsedMs", System.currentTimeMillis() - begin);
            data.put("hits", best.hits());
            return data;
        }

        RetrievalService.SearchMode m = mode == null
                ? RetrievalService.SearchMode.from(retrievalService.defaultModeCode())
                : RetrievalService.SearchMode.from(mode);
        List<RetrievalService.Hit> hits = retrievalService.search(q, k, m);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", q);
        data.put("mode", m.code());
        data.put("modeLabel", m.label());
        data.put("topK", topK);
        data.put("count", hits.size());
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        data.put("hits", hits);
        return data;
    }

    /**
     * 三路对比：同一个问题，向量 / BM25 / 混合 各返回 topK。
     * 例子：GET /api/search/compare?q=违纪处分有哪几种&topK=3
     *
     * <p>这个接口是调效果的"显微镜"：一眼看出某一路漏了什么、另一路有没有捞回来。
     */
    @GetMapping("/search/compare")
    public Map<String, Object> compare(@RequestParam("q") String q,
                                       @RequestParam(defaultValue = "3") int topK) {
        long begin = System.currentTimeMillis();
        List<RetrievalService.ModeResult> results = retrievalService.compare(q, Math.max(1, topK));

        List<Map<String, Object>> modes = new ArrayList<>();
        for (RetrievalService.ModeResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", r.mode());
            item.put("modeLabel", r.label());
            item.put("count", r.hits().size());
            item.put("hits", r.hits());
            modes.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", q);
        data.put("topK", topK);
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        data.put("modes", modes);
        return data;
    }

    /** 看 BM25 索引状态：GET /api/search/stats */
    @GetMapping("/search/stats")
    public Map<String, Object> stats() {
        return retrievalService.bm25Stats();
    }

    /**
     * 看改写器的调用统计：GET /api/search/rewrite-stats
     *
     * <p>评测一轮要 3~4 分钟，中途若有改写失败（限流/超时），数字会悄悄退化成"没改写"。
     * 先看这里的 attempts / successes / failures，才能区分"方法无效"和"这次没跑成"。
     */
    @GetMapping("/search/rewrite-stats")
    public Map<String, Object> rewriteStats() {
        return queryRewriter.stats();
    }

    /**
     * 看这一次查询会用什么融合权重：GET /api/search/weights?q=xxx
     *
     * <p>标定自适应权重的门槛值就靠这个接口：把 dev 集所有题跑一遍，
     * 看 signal 的分布，再决定 bm25-strong-avg 取多少。
     */
    /**
     * 看查询改写的效果：GET /api/search/rewrite?q=借的书忘还了怎么算
     *
     * <p>返回原句、改写句、本次 BM25 信号，用来判断"改写到底有没有把话翻译对"。
     * 改写关掉时 rewritten 为空，enabled=false。
     */
    @GetMapping("/search/rewrite")
    public Map<String, Object> rewrite(@RequestParam("q") String q) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", q);
        data.put("enabled", queryRewriter.enabled());
        data.put("model", queryRewriter.modelName());
        data.put("signal", Math.round(retrievalService.explainWeights(q).signal() * 100) / 100.0);
        String rewritten = queryRewriter.rewrite(q);
        data.put("rewritten", rewritten);
        data.put("changed", rewritten != null && !rewritten.equals(q));
        return data;
    }

    @GetMapping("/search/weights")
    public Map<String, Object> weights(@RequestParam("q") String q) {
        RetrievalService.WeightDecision d = retrievalService.explainWeights(q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", q);
        data.put("wVector", d.wVector());
        data.put("wBm25", d.wBm25());
        data.put("signal", Math.round(d.signal() * 100) / 100.0);
        data.put("reason", d.reason());
        return data;
    }
}
