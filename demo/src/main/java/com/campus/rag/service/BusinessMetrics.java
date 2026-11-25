package com.campus.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 业务指标（除了 HTTP 自带的 http.server.requests 之外，面试真正会被问的那几个）。
 *
 * <p>三类：
 * <ol>
 *   <li><b>Counter</b>：问答次数（按 mode / gated / cached 打标签）、检索层拒答次数、
 *       缓存命中与未命中、精排降级次数、Agent 任务结局；</li>
 *   <li><b>Timer</b>：问答耗时（按 mode 分），能直接出 P50/P95/P99；</li>
 *   <li><b>Gauge</b>：库内文档数、块数（在 MetricsConfig 里注册）。</li>
 * </ol>
 *
 * <p><b>标签基数是杀器</b>：绝不把问题原文、用户 id 当标签，
 * 只允许 mode ∈ {sync, stream, agent}、gated ∈ {true,false}、cached ∈ {true,false}
 * 这类有限枚举。否则 Prometheus 的时间序列会爆掉。
 */
@Component
public class BusinessMetrics {

    /** 标签组合是有限枚举，缓存 Counter/Timer 实例，避免每次请求都去 registry 里查 */
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 一次问答结束（同步 / 流式 / Agent 都走这里） */
    public void qaFinished(String mode, boolean gated, boolean cached, long elapsedMs, int totalTokens) {
        counter("campus_qa_requests_total", mode, gated, cached).increment();
        timer("campus_qa_latency", mode).record(elapsedMs, TimeUnit.MILLISECONDS);
        if (totalTokens > 0) {
            counter("campus_qa_tokens_total", mode, false, cached).increment(totalTokens);
        }
    }

    /** 拒绝回答（layer=retrieval 表示检索层就拦下了，没调模型） */
    public void qaRefused(String layer) {
        get("campus_qa_refusals_total", "layer", layer).increment();
    }

    /** 缓存读写结果 */
    public void cacheAccess(boolean hit) {
        get("campus_cache_access_total", "result", hit ? "hit" : "miss").increment();
    }

    /** 精排不可用，退回 RRF 顺序 */
    public void rerankFallback(String reason) {
        get("campus_rerank_fallback_total", "reason", reason).increment();
    }

    /** Agent 任务结局：DONE / DEGRADED / FAILED */
    public void agentFinished(String status, int steps) {
        get("campus_agent_runs_total", "status", status).increment();
        get("campus_agent_steps_total", "status", status).increment(steps);
    }

    private Counter counter(String name, String mode, boolean gated, boolean cached) {
        String key = name + "|" + mode + "|" + gated + "|" + cached;
        return counters.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("mode", mode)
                .tag("gated", Boolean.toString(gated))
                .tag("cached", Boolean.toString(cached))
                .register(registry));
    }

    private Timer timer(String name, String mode) {
        String key = name + "|" + mode;
        return timers.computeIfAbsent(key, k -> Timer.builder(name)
                .tag("mode", mode)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    private Counter get(String name, String tagKey, String tagValue) {
        String key = name + "|" + tagValue;
        return counters.computeIfAbsent(key, k -> Counter.builder(name).tag(tagKey, tagValue).register(registry));
    }
}
