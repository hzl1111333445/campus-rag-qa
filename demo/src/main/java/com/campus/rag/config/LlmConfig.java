package com.campus.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把 LlmProperties 注册进容器。
 * 加了这一句，Spring 才会去读 application.yml 里的 campus.llm.* 并填进 LlmProperties。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
}
