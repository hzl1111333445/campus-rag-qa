package com.campus.rag.controller;

import com.campus.rag.service.EvalJobService;
import com.campus.rag.service.EvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final EvalJobService jobService;

    public EvalController(EvalService evalService, EvalJobService jobService) {
        this.evalService = evalService;
        this.jobService = jobService;
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

    /**
     * 提交一轮评测（异步）：立刻返回 jobId，用 /jobs/{id} 轮询进度。
     *
     * <p>为什么不直接用下面的 /run？因为一轮要几分钟，浏览器 60 秒就超时了。
     * /run 保留给 curl 和脚本（脚本可以自己放宽超时）。
     */
    @PostMapping("/jobs")
    public Map<String, Object> submitJob(@RequestParam(defaultValue = "3") int topK) {
        int k = Math.max(1, Math.min(topK, 10));
        int total = evalService.info().get("questionCount") instanceof Integer n ? n : 0;
        String jobId = jobService.submit(k, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "任务已提交");
        body.put("data", Map.of("jobId", jobId, "topK", k, "total", total));
        return body;
    }

    /** 查任务进度；跑完时顺带把完整报告一起返回，前端不用再请求一次 */
    @GetMapping("/jobs/{id}")
    public Map<String, Object> getJob(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        EvalJobService.Snapshot snapshot = jobService.get(id);
        if (snapshot == null) {
            body.put("code", 404);
            body.put("message", "任务不存在或已被淘汰：" + id);
            body.put("data", null);
            return body;
        }
        body.put("code", 0);
        body.put("message", snapshot.status());
        body.put("data", snapshot);
        return body;
    }

    /** 最近的任务（刷新页面后能找回正在跑的那一轮） */
    @GetMapping("/jobs")
    public Map<String, Object> listJobs() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "成功");
        body.put("data", jobService.list());
        return body;
    }
}
