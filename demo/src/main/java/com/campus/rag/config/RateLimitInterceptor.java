package com.campus.rag.config;

import com.campus.rag.service.CacheKeys;
import com.campus.rag.service.CacheService;
import com.campus.rag.service.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 问答接口限流：每个"人"每分钟最多 N 次。
 *
 * <p><b>为什么需要它</b>：一次问答要调 DeepSeek（按 token 计费）+ 向量化 + 精排，
 * 单次成本约 1~2 秒的算力与真金白银。接口一旦被人（或脚本）连点，
 * 轻则响应变慢，重则账单暴增。限流是最便宜的一道保护。
 *
 * <p><b>为什么按"人"而不是按 IP</b>：校园网、手机热点会让很多人共用出口 IP，
 * 按 IP 限流会把一个宿舍 4 个人算成一个人；登录用户按用户 id 计，未登录的才退回 IP。
 *
 * <p><b>为什么用固定窗口而不是令牌桶</b>：这是演示/毕设项目，固定窗口（每分钟一个计数器）
 * 用一个 Redis `INCR + EXPIRE` 就能实现，逻辑一眼可见。代价是"窗口边界突刺"
 * （第 59 秒打满 30 次、第 61 秒又打满 30 次）——真实业务量下影响很小，
 * 而令牌桶要多存一个"上次补充时间"，调试成本高。取舍理由写进注释，答辩可以讲。
 *
 * <p><b>为什么带 {@code X-No-Persist} 的请求不计数</b>：评测脚本与压测一次要打上百次，
 * 而且它们不是"用户行为"。给机器留显式旁路，比事后调阈值可靠。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final CacheService cacheService;
    private final int limitPerMinute;

    public RateLimitInterceptor(CacheService cacheService,
                                @Value("${campus.cache.rate-limit-per-minute:30}") int limitPerMinute) {
        this.cacheService = cacheService;
        this.limitPerMinute = Math.max(1, limitPerMinute);
        log.info("问答限流：每个用户每分钟 {} 次（Redis 不可用时自动放行）", this.limitPerMinute);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 机器调用（评测/压测）不计数
        if (request.getHeader("X-No-Persist") != null) {
            return true;
        }
        if (limitPerMinute <= 0 || !cacheService.enabled()) {
            return true;
        }

        String subject = subject(request);
        long minute = System.currentTimeMillis() / 60_000L;
        String key = CacheKeys.rateLimit(subject, minute);
        long count = cacheService.incrementWithWindow(key, Duration.ofMinutes(2));

        // 0 表示 Redis 不可用：宁可放行，也不要因为缓存故障把用户挡在门外
        if (count == 0) {
            return true;
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(limitPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limitPerMinute - count)));

        if (count > limitPerMinute) {
            long secondsLeft = 60 - (System.currentTimeMillis() / 1000L) % 60;
            log.warn("触发限流：subject={} 第 {} 次/分钟", subject, count);
            throw new RateLimitException(
                    "请求过于频繁：每分钟最多 " + limitPerMinute + " 次，请 " + secondsLeft + " 秒后重试",
                    limitPerMinute, secondsLeft);
        }
        return true;
    }

    /** 登录用户按 id 计，未登录退回 IP（AuthInterceptor 已把身份放进 request attribute）。 */
    private String subject(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (userId != null) {
            return "u" + userId;
        }
        String ip = request.getRemoteAddr();
        return "ip" + (ip == null ? "unknown" : ip);
    }
}
