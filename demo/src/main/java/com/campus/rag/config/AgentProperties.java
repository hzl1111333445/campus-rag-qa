package com.campus.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent（ReAct）开关与限额。默认关闭：关掉时接口不注册，主链路完全不受影响。
 */
@Component
@ConfigurationProperties(prefix = "campus.agent")
public class AgentProperties {

    /** 是否启用 /api/agent/**；默认 false。 */
    private boolean enabled = false;

    /** 最多几步（每步一次模型调用 + 一次工具调用）。 */
    private int maxSteps = 6;

    /** 单条 Observation 截断长度，避免把上下文撑爆。 */
    private int maxObservationChars = 2000;

    /** 同一工具同一参数允许重复几次，超过即中止（防死循环）。 */
    private int maxSameCall = 2;

    /** 默认检索条数。 */
    private int defaultTopK = 5;

    /** 一次 Agent 执行允许消耗的总 token 上限（prompt + completion 累计），超了直接降级。 */
    private int maxTotalTokens = 12000;

    /** 价格（元 / 百万 token），默认值是示例价，请按模型官网现价改；只用于前端"本次花费≈"展示。 */
    private double priceInputPerMillion = 2.0;

    /** 输出单价（元 / 百万 token）。 */
    private double priceOutputPerMillion = 8.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMaxObservationChars() {
        return maxObservationChars;
    }

    public void setMaxObservationChars(int maxObservationChars) {
        this.maxObservationChars = maxObservationChars;
    }

    public int getMaxSameCall() {
        return maxSameCall;
    }

    public void setMaxSameCall(int maxSameCall) {
        this.maxSameCall = maxSameCall;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public int getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(int maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }

    public double getPriceInputPerMillion() {
        return priceInputPerMillion;
    }

    public void setPriceInputPerMillion(double priceInputPerMillion) {
        this.priceInputPerMillion = priceInputPerMillion;
    }

    public double getPriceOutputPerMillion() {
        return priceOutputPerMillion;
    }

    public void setPriceOutputPerMillion(double priceOutputPerMillion) {
        this.priceOutputPerMillion = priceOutputPerMillion;
    }
}