package com.campus.rag.controller;

import com.campus.rag.service.RetrievalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索接口：给前端和调试用的"只检索、不生成回答"入口。
 *
 * <p>为什么单独开一个？因为调模型回答要花钱、要等几秒，
 * 而检索本身很快。调效果时先看检索对不对，比看最终回答说错话更好定位问题。
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final RetrievalService retrievalService;

    public SearchController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 例子：GET /api/search?q=违纪处分有哪几种&topK=3
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(defaultValue = "3") int topK) {
        long begin = System.currentTimeMillis();
        List<RetrievalService.Hit> hits = retrievalService.search(q, Math.max(1, topK));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", q);
        data.put("topK", topK);
        data.put("count", hits.size());
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        data.put("hits", hits);
        return data;
    }
}
