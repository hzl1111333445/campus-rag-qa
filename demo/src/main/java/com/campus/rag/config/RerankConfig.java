package com.campus.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把 RerankProperties 注册进容器。
 *
 * <p>加了这一句，Spring 才会去读 application.yml 里的 {@code campus.rerank.*}
 * 并填进 RerankProperties。少了它，RerankClient 注入就会失败（找不到 Bean）。
 */
@Configuration
@EnableConfigurationProperties(RerankProperties.class)
public class RerankConfig {
}
