package com.campus.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册拦截器（顺序很重要）。
 *
 * <p>只拦 {@code /api/**}，静态资源（前端打包后的 index.html、js、css）不拦 ——
 * 否则没登录连登录页面都打不开。
 *
 * <p><b>顺序说明</b>：
 * <ol>
 *   <li>{@link AuthInterceptor} 先跑：它负责"这是谁"，并把 userId/role 放进 request attribute</li>
 *   <li>{@link RateLimitInterceptor} 后跑：它按 userId 计数，所以必须等身份识别完</li>
 * </ol>
 * 反过来会怎样：未登录用户全被算成同一个 IP，登录用户也拿不到 id，限流粒度就错了。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**").order(1);
        // 只给问答接口限流：检索调试、评测看板的调用量小，且本身就是管理员工具
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/qa", "/api/qa/stream").order(2);
    }
}
