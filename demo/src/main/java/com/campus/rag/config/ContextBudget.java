package com.campus.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上下文预算表：把"上下文怎么分配、什么时候压缩"写成一张可以念出来的常数表。
 *
 * <p><b>为什么单独一个类</b>：预算散落在各处的 {@code @Value} 里，就没人能回答
 * "我们的上下文到底怎么分配的"。集中成一张表后，启动日志能打出来、文档能抄、
 * 面试时能照着讲——这比"我们做了摘要"具体得多。
 *
 * <p><b>8 个常数怎么来的</b>（每一个都有理由，不是拍的）：
 * <ul>
 *   <li>{@code windowTokens=8000}：DeepSeek 窗口远大于此，但我们主动收窄——
 *       资料 3 块 + 历史 3 轮 + 系统提示词约 3200 token，留一倍余量给长回答与追问补全</li>
 *   <li>{@code triggerPct=0.60}：达到 60% 才考虑折叠。DSH 默认 75%，我们窗口小所以提前</li>
 *   <li>{@code emergencyPct=0.90}：到 90% 不再看"增长量"，立刻折（DSH 是 95%）</li>
 *   <li>{@code growthTokens=3000}：距上次折叠只增长不到 3000 token 就不要折——
 *       否则每轮都折一次，既花钱又把摘要写碎（DSH 用 50000 做同一件事）</li>
 *   <li>{@code keepRecentTurns=3} / {@code keepRecentTokens=1200}：保护带。
 *       最近 3 轮永远保留原文，因为指代（"那/它/这个"）几乎都指向最近的内容</li>
 *   <li>{@code summaryMaxChars=240}：摘要段上限。再长就开始挤占【资料】的预算</li>
 * </ul>
 *
 * <p><b>为什么用字符数估算而不是精确 token</b>：精确计数要引 tokenizer 依赖，
 * 而且中文按 1 字 ≈ 0.6 token 估算的误差（±15%）对"要不要折"这个决策不敏感；
 * 真正的 token 消耗由 {@code ChatClient.chatWithUsage} 的 usage 回填，用于成本核算。
 */
@Component
public class ContextBudget {

    /** 总开关：关掉时行为与"没有摘要"完全一致（可做 A/B 对照）。 */
    public final boolean summaryEnabled;

    public final int windowTokens;
    public final double triggerPct;
    public final double emergencyPct;
    public final int growthTokens;

    public final int keepRecentTurns;
    public final int keepRecentTokens;

    public final int summaryMaxChars;
    public final int minMessagesToFold;
    public final int minFoldChars;
    public final int maxSegmentChars;

    public ContextBudget(
            @Value("${campus.context.summary.enabled:false}") boolean summaryEnabled,
            @Value("${campus.context.summary.window-tokens:1200}") int windowTokens,
            @Value("${campus.context.summary.trigger-pct:0.60}") double triggerPct,
            @Value("${campus.context.summary.emergency-pct:0.90}") double emergencyPct,
            @Value("${campus.context.summary.growth-tokens:300}") int growthTokens,
            @Value("${campus.context.summary.keep-recent-turns:5}") int keepRecentTurns,
            @Value("${campus.context.summary.keep-recent-tokens:600}") int keepRecentTokens,
            @Value("${campus.context.summary.max-chars:240}") int summaryMaxChars,
            @Value("${campus.context.summary.min-messages:10}") int minMessagesToFold,
            @Value("${campus.context.summary.min-fold-chars:300}") int minFoldChars,
            @Value("${campus.context.summary.max-segment-chars:6000}") int maxSegmentChars) {
        this.summaryEnabled = summaryEnabled;
        this.windowTokens = Math.max(1000, windowTokens);
        this.triggerPct = clamp(triggerPct, 0.1, 0.95);
        this.emergencyPct = Math.max(this.triggerPct + 0.05, clamp(emergencyPct, 0.2, 0.99));
        this.growthTokens = Math.max(0, growthTokens);
        this.keepRecentTurns = Math.max(0, keepRecentTurns);
        this.keepRecentTokens = Math.max(0, keepRecentTokens);
        this.summaryMaxChars = Math.max(60, summaryMaxChars);
        this.minMessagesToFold = Math.max(4, minMessagesToFold);
        this.minFoldChars = Math.max(100, minFoldChars);
        this.maxSegmentChars = Math.max(500, maxSegmentChars);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    /** 给启动日志用：一眼能看到当前预算表。 */
    public String describe() {
        return String.format(
                "摘要=%s 窗口=%dtoken 触发=%.0f%%/紧急=%.0f%% 增长=%dtoken "
                        + "保护带=%d轮/%dtoken 摘要上限=%d字 最小折叠=%d条/%d字",
                summaryEnabled ? "开" : "关", windowTokens, triggerPct * 100, emergencyPct * 100,
                growthTokens, keepRecentTurns, keepRecentTokens, summaryMaxChars,
                minMessagesToFold, minFoldChars);
    }
}
