package com.campus.rag.controller;

import com.campus.rag.service.EvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测接口。
 *
 * <p>两个入口：
 * <ul>
 *   <li>GET /api/eval/testset —— 看看题集加载情况（多少题、什么题型、有哪几种配置）</li>
 *   <li>GET /api/eval/run?topK=3 —— 跑一轮，返回完整对照表</li>
 * </ul>
 *
 * <p>为什么用 GET 而不是 POST？因为它不改任何数据，只是"算一遍给你看"，
 * 幂等、可以随便刷新、可以直接贴进浏览器地址栏。真跑起来要几十秒，
 * 一会儿觉得慢了别以为是卡死——36 题每题都要调一次 embedding 接口。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping("/testset")
    public Map<String, Object> testset() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "成功");
        body.put("data", evalService.info());
        return body;
    }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "3") int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("code", 0);
            body.put("message", "成功");
            body.put("data", evalService.run(Math.max(1, Math.min(topK, 10))));
        } catch (RuntimeException e) {
            body.put("code", 500);
            body.put("message", "评测失败：" + e.getMessage());
            body.put("data", null);
        }
        return body;
    }
}
