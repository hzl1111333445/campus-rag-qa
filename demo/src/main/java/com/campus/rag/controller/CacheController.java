package com.campus.rag.controller;

import com.campus.rag.service.CacheKeys;
import com.campus.rag.service.CacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存运维接口（放在 {@code /api/dev/} 下，自动被 AuthInterceptor 归入"仅管理员"）。
 *
 * <p>为什么需要这组接口：缓存最怕"加了但没生效"——所以必须有办法直接看到
 * 命中数、未命中数、错误数，以及手动清理。仅凭日志判断太慢。
 */
@RestController
@RequestMapping("/api/dev/cache")
public class CacheController {

    private final CacheService cacheService;

    public CacheController(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** 命中率、错误数、是否连得上 Redis。 */
    @GetMapping("/stats")
    public CacheService.Stats stats() {
        return cacheService.stats();
    }

    /**
     * 按前缀清理。{@code target} 只接受 qa / emb / rate / all —— 不开放任意前缀，
     * 避免误删其他业务键。
     */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestParam(defaultValue = "qa") String target) {
        String prefix = switch (target) {
            case "qa" -> CacheKeys.PREFIX_QA;
            case "emb" -> CacheKeys.PREFIX_EMB;
            case "rate" -> CacheKeys.PREFIX_RATE;
            case "all" -> "";
            default -> throw new IllegalArgumentException("target 只支持 qa / emb / rate / all");
        };
        int deleted = prefix.isEmpty()
                ? cacheService.deleteByPrefix("qa:v1:") + cacheService.deleteByPrefix("emb:v1:")
                + cacheService.deleteByPrefix("rl:v1:")
                : cacheService.deleteByPrefix(prefix);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", target);
        body.put("deleted", deleted);
        return body;
    }

    /** 重置命中统计（压测前用，避免历史数据混进来）。 */
    @PostMapping("/reset-stats")
    public Map<String, Object> resetStats() {
        cacheService.resetStats();
        return Map.of("reset", true);
    }
}
