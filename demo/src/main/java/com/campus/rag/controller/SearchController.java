package com.campus.rag.controller;

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

    public SearchController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 例子：GET /api/search?q=违纪处分有哪几种&topK=3&mode=hybrid
     *
     * @param mode vector / bm25 / hybrid，不传用配置里的默认值
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(defaultValue = "3") int topK,
                                      @RequestParam(required = false) String mode) {
        long begin = System.currentTimeMillis();
        RetrievalService.SearchMode m = mode == null
                ? RetrievalService.SearchMode.from(retrievalService.defaultModeCode())
                : RetrievalService.SearchMode.from(mode);
        List<RetrievalService.Hit> hits = retrievalService.search(q, Math.max(1, topK), m);

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
}
