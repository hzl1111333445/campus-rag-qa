package com.campus.rag.config;

import com.campus.rag.service.AuthService;
import com.campus.rag.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 权限拦截器：把"谁能调用哪个接口"这件事集中在一个地方，不散落在各个 Controller。
 *
 * <p>三条规则（本项目当前的取舍）：
 * <ol>
 *   <li><b>公开</b>：健康检查、智能问答、会话、检索预览 —— 学生和游客都能用，方便演示；</li>
 *   <li><b>仅管理员</b>：文档管理（上传/删除/重切/向量化）、/api/dev/** 调试接口、/api/eval/** 评测；</li>
 *   <li>其余默认放行（新增接口时请自己判断要不要加进下面的管理员清单）。</li>
 * </ol>
 *
 * <p>注意：拦截器只做"入口检查"。真正的数据隔离（比如学生只能看自己的会话）
 * 属于业务层的事，不在这一层解决。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 这些前缀下的接口必须管理员身份 */
    private static final List<String> ADMIN_PREFIXES = List.of(
            "/api/document",
            "/api/dev",
            "/api/eval"
    );

    private final TokenService tokenService;
    private final ObjectMapper mapper;

    public AuthInterceptor(TokenService tokenService, ObjectMapper mapper) {
        this.tokenService = tokenService;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 浏览器跨域预检请求直接放行，否则前端一律报 CORS 错
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (!needsAdmin(path)) {
            return true;
        }

        Map<String, Object> payload = tokenService.verify(extractToken(request));
        if (payload == null) {
            return reject(response, 401, "请先登录（缺少或无效的 Authorization: Bearer <token>）");
        }
        if (!AuthService.ROLE_ADMIN.equals(String.valueOf(payload.get("r")))) {
            return reject(response, 403, "该功能仅管理员可用，当前角色：" + payload.get("r"));
        }
        // 把身份挂到 request 上，Controller 想用可以直接取（暂时没用到，留着扩展）
        request.setAttribute("authUserId", payload.get("uid"));
        request.setAttribute("authRole", payload.get("r"));
        return true;
    }

    private boolean needsAdmin(String path) {
        return ADMIN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        return header.startsWith(prefix) ? header.substring(prefix.length()).trim() : header.trim();
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("status", status, "message", message));
        return false;
    }
}
