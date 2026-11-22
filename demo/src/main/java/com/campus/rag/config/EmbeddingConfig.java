package com.campus.rag.config;

import com.campus.rag.service.EmbeddingClient;
import com.campus.rag.service.OpenAiEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量化的装配类。
 *
 * <p>它只干一件事：根据配置里的 {@code campus.embedding.provider}，
 * 决定往容器里放哪个 {@link EmbeddingClient} 实现。
 *
 * <p>注意上层代码（EmbeddingService）只写了 {@code EmbeddingClient}，
 * 从来没写过具体实现类的名字。换 provider 只需要改 yml 一行。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties props) {
        String provider = props.getProvider() == null ? "" : props.getProvider().trim();
        if ("openai".equalsIgnoreCase(provider)) {
            if (props.getApiKey() == null || props.getApiKey().isBlank()) {
                throw new IllegalStateException(
                        "campus.embedding.api-key 是空的。请在 application-local.yml 里填上 key，"
                                + "或者把 provider 改成 hash（离线假向量）。");
            }
            log.info("向量化使用真实模型：{}，维度 {}，接口 {}", props.getModel(), props.getDim(), props.getBaseUrl());
            return new OpenAiEmbeddingClient(props);
        }
        throw new IllegalStateException(
                "未知的 campus.embedding.provider: " + props.getProvider() + "，目前只支持 openai。");
    }
}
