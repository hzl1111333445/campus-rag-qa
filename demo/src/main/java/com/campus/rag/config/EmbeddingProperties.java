package com.campus.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化（embedding）相关的配置，对应 application.yml 里的 campus.embedding.*
 *
 * <p>为什么用 @ConfigurationProperties 而不是一堆 @Value？
 * 因为这里的配置是一组相关的东西（provider/model/dim/baseUrl/apiKey），
 * 绑到一个类上以后：①可以在 yml 里按层级写 ②IDE 有提示 ③改配置不用改代码。
 *
 * <p>provider 有两个值：
 * <ul>
 *   <li>{@code hash}  —— 哈希假向量，不用联网、不用 key，只用来跑通链路</li>
 *   <li>{@code openai} —— 任何 OpenAI 兼容接口（硅基流动、阿里云百炼都行）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "campus.embedding")
public class EmbeddingProperties {

    /** hash / openai */
    private String provider = "hash";

    /** 模型名，比如 BAAI/bge-m3 */
    private String model = "BAAI/bge-m3";

    /** 向量维度。bge-m3 是 1024，哈希版是 512。配错了会报"维度不一致" */
    private int dim = 1024;

    /** 接口地址，硅基流动是 https://api.siliconflow.cn/v1 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** API Key。不要写在这里，写在 application-local.yml 或环境变量里 */
    private String apiKey = "";

    /** 一次请求发多少段文字。太大可能超时或被限流 */
    private int batchSize = 16;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDim() {
        return dim;
    }

    public void setDim(int dim) {
        this.dim = dim;
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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
