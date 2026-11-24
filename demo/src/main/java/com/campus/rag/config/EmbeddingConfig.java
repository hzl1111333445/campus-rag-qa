package com.campus.rag.config;

import com.campus.rag.service.CacheService;
import com.campus.rag.service.CachedEmbeddingClient;
import com.campus.rag.service.EmbeddingClient;
import com.campus.rag.service.OpenAiEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * 真正的客户端在这层里面构造，外面统一套一层 {@link CachedEmbeddingClient}。
     *
     * <p>为什么要套：检索时每次提问都要把问题向量化（一次 HTTP 往返，150~300ms），
     * 而同一个问题的向量是完全确定的，缓存起来第二次就能省掉这一步。
     * 套在 Bean 上，入库的批量路径也自动享受同样的缓存，不用改两处。
     */
    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties props,
                                          CacheService cacheService,
                                          ObjectMapper objectMapper) {
        return new CachedEmbeddingClient(buildDelegate(props), cacheService, objectMapper);
    }

    private EmbeddingClient buildDelegate(EmbeddingProperties props) {
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
