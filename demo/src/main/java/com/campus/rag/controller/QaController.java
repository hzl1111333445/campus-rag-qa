package com.campus.rag.controller;

import com.campus.rag.service.QaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 问答接口：检索 + 生成 + 引用。
 *
 * <p>两个入口：
 * <ul>
 *   <li>POST /api/qa        —— 真回答（会调大模型，花钱、慢）</li>
 *   <li>GET  /api/qa/preview —— 只看检索到了什么、提示词长什么样（不花钱，调试用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    /** 请求体：{"question":"违纪处分有哪几种","topK":3} */
    public record AskRequest(String question, Integer topK) {
    }

    @PostMapping
    public QaService.QaAnswer ask(@RequestBody AskRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        int topK = req.topK() == null ? 3 : Math.max(1, req.topK());
        return qaService.ask(req.question().trim(), topK);
    }

    /** 调试：只看检索结果和拼好的提示词，不调模型 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("q") String q,
                                       @RequestParam(defaultValue = "3") int topK) {
        return qaService.preview(q, Math.max(1, topK));
    }
}
