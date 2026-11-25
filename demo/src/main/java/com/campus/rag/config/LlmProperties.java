package com.campus.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大模型（对话模型）的配置。
 *
 * <p>为什么单独搞一个类，而不是在业务代码里写死 "https://api.deepseek.com"？
 * 因为要能换：今天是 DeepSeek，明天可能换通义、换智谱。
 * 换的时候只改 application-local.yml 一行，不动 Java 代码。
 *
 * <p>这些值都来自 application.yml 的 campus.llm.* ，
 * 其中 api-key 的真实值在 application-local.yml（不进 Git）。
 */
@ConfigurationProperties(prefix = "campus.llm")
public class LlmProperties {

    /** 接口地址。DeepSeek 是 https://api.deepseek.com/v1（注意带 /v1） */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型名。deepseek-chat 便宜够用；deepseek-reasoner 更慢更贵 */
    private String model = "deepseek-chat";

    /** 门禁卡 */
    private String apiKey = "";

    /**
     * 温度：0 = 每次都一样、最保守；1 = 更有创造性但更容易胡说。
     * 问答场景要"忠实资料"，所以调低到 0.2。
     */
    private double temperature = 0.2;

    /** 最多生成多少 token（约等于多少个字）。800 够写 300 字回答 */
    private int maxTokens = 800;

    /** 超时秒数。模型偶尔会慢，给 60 秒 */
    private int timeoutSeconds = 60;

    /** 输入单价（元 / 百万 token）。默认是示例价，请按模型官网现价改；只用于"本次花费≈"展示。 */
    private double priceInputPerMillion = 2.0;

    /** 输出单价（元 / 百万 token）。 */
    private double priceOutputPerMillion = 8.0;

    /**
     * 是否启用"两段式生成"：①先让模型只摘原句 ②再让它只用这些原句组答案。
     *
     * <p>默认开。理由见 docs/16：46 道答不好的题里 30 道是"资料已经在 Top3、模型还编细节"，
     * 单段式下模型会边找边写、顺手补常识；两段式把"找"和"写"拆开，它就没有机会想起来。
     * 关掉可以回退到单段式（对比实验用）。
     */
    private boolean twoStage = true;

    public boolean isTwoStage() {
        return twoStage;
    }

    public void setTwoStage(boolean twoStage) {
        this.twoStage = twoStage;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
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
