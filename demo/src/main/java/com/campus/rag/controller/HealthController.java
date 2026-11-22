package com.campus.rag.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口：只用来证明「服务起来了、能返回 JSON」。
 * 浏览器访问 http://localhost:8081/api/health 就能看到结果。
 */
@RestController                       // 这个类的每个方法都返回数据（自动转 JSON），不是网页
@RequestMapping("/api/health")        // 这个类的所有接口都以 /api/health 开头
public class HealthController {

    // 从 application.yml 里读 spring.application.name 的值
    @Value("${spring.application.name}")
    private String appName;

    @GetMapping                       // 处理 GET /api/health
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("appName", appName);
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("time", LocalDateTime.now().toString());
        return data;
    }
}
