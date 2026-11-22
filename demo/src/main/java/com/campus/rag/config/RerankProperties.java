package com.campus.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重排（rerank）相关的配置。
 *
 * <p><b>重排是干什么的</b>：粗排（向量/BM25）从 414 个块里挑出"看起来像"的前 N 个，
 * 快但粗；精排把这 N 个块逐条和问题一起送进 Cross-Encoder 模型，
 * 由模型判断"这一段到底能不能回答问题"，再重新排序。
 *
 * <p><b>为什么要有 enabled 开关</b>：重排每道题要多花一次模型调用（1~2 秒）。
 * 开发调试时不希望每次问一句就等两秒，所以可以关掉；
 * 关掉时检索照常工作，只是没有精排这一步（自动降级，不会报错）。
 */
@ConfigurationProperties(prefix = "campus.rerank")
public class RerankProperties {

    /** 是否启用重排。false = 跳过精排，直接用粗排结果 */
    private boolean enabled = false;

    /** 服务地址。硅基流动的 OpenAI 兼容地址以 /v1 结尾 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** API Key。留空且 enabled=true 时，服务启动会报错提示 */
    private String apiKey = "";

    /** 重排模型名 */
    private String model = "BAAI/bge-reranker-v2-m3";

    /**
     * 送给精排的候选数。
     * 太大 = 每次调用很慢很贵；太小 = 正确块可能压根没进候选。
     * 我们两道 badcase 的正确块排第 4 名，所以 10 已经足够。
     */
    private int candidates = 10;

    /** 单个文档截断长度：精排模型有输入上限，且长文本主要看开头 */
    private int maxDocChars = 600;

    /** 超时秒数。超过就降级，不让用户干等 */
    private int timeoutSeconds = 15;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getCandidates() {
        return candidates;
    }

    public void setCandidates(int candidates) {
        this.candidates = candidates;
    }

    public int getMaxDocChars() {
        return maxDocChars;
    }

    public void setMaxDocChars(int maxDocChars) {
        this.maxDocChars = maxDocChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
