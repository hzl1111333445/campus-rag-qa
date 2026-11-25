package com.campus.rag.config;

import com.campus.rag.service.AuthService;
import com.campus.rag.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把 {@code /actuator/**} 收成"仅管理员"，只放行 {@code /actuator/health}。
 *
 * <p><b>为什么用 Filter 而不是 HandlerInterceptor</b>：Actuator 的端点是
 * 由 Spring Boot 自己注册的一套 handler mapping 处理的，
 * {@code WebMvcConfigurer#addInterceptors} 拦不到它们（实测拦不住，无令牌照样 200）。
 * Filter 在 Servlet 层，不管后面是谁处理请求都逃不掉。
 *
 * <p>放行 health 是因为：容器探活、负载均衡健康检查、你自己 curl 都要它，
 * 而它只返回 {@code {"status":"UP"}}，不泄露内部结构。
 * metrics / prometheus / env / beans / configprops 这些才是有信息量的东西。
 */
@Component
public class ActuatorGuardFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final ObjectMapper mapper;

    public ActuatorGuardFilter(TokenService tokenService, ObjectMapper mapper) {
        this.tokenService = tokenService;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/actuator")) {
            return true;
        }
        // 健康检查公开：只返回 status，不泄露内部结构
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            write(response, 401, "看指标需要管理员登录");
            return;
        }
        Map<String, Object> payload = tokenService.verify(header.substring("Bearer ".length()).trim());
        if (payload == null) {
            write(response, 401, "登录已过期，请重新登录");
            return;
        }
        // 令牌里的角色字段叫 r（uid / u / r 三个短键，省流量），不是 role
        if (!AuthService.ROLE_ADMIN.equals(String.valueOf(payload.get("r")))) {
            write(response, 403, "该功能仅管理员可用");
            return;
        }
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(mapper.writeValueAsString(Map.of("status", status, "message", message))
                .getBytes(StandardCharsets.UTF_8));
    }
}
