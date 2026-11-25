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
 * 认证与授权入口：把"谁能调用哪个接口"集中在一个地方，不散落在各个 Controller。
 *
 * <p>三层规则：
 * <ol>
 *   <li><b>公开</b>：健康检查、智能问答（含匿名提问，评测脚本要用）、
 *       检索预览 —— 不需要令牌；</li>
 *   <li><b>需登录</b>：{@code /api/session/**} 会话与历史 —— 这是"我的数据"，
 *       没登录就没有"我"；</li>
 *   <li><b>仅管理员</b>：文档管理、/api/dev 调试、/api/eval 评测。</li>
 * </ol>
 *
 * <p>关键点：**只要带了有效令牌，无论访问哪个接口，都会把身份挂到 request 上**
 * （属性名 {@code authUserId} / {@code authRole}）。这样 QaController 这种公开接口
 * 也能知道"提问的人是谁"，从而把会话记到正确的人名下。
 * 注意这不是"校验"，是"识别"——识别失败不拦，交给后面的规则判断要不要拦。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 这些前缀下的接口必须管理员身份 */
    private static final List<String> ADMIN_PREFIXES = List.of(
            "/api/document",
            "/api/dev",
            "/api/eval",
            // 检索调试接口会暴露内部分数、改写句与阈值，属于"调参工具"，不对学生开放
            "/api/search",
            // 用户管理：看用户列表、停用账号、重置密码，只有管理员能做
            "/api/user"
    );

    /** 这些前缀下的接口必须登录（但不需要管理员） */
    private static final List<String> LOGIN_REQUIRED_PREFIXES = List.of(
            "/api/session"
    );

    public static final String ATTR_USER_ID = "authUserId";
    public static final String ATTR_ROLE = "authRole";
    public static final String ATTR_USERNAME = "authUsername";

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

        // 第一步：识别身份（有令牌且有效就挂上，失败不报错）
        Map<String, Object> payload = tokenService.verify(extractToken(request));
        Long userId = null;
        boolean admin = false;
        if (payload != null) {
            userId = payload.get("uid") == null ? null : ((Number) payload.get("uid")).longValue();
            admin = AuthService.ROLE_ADMIN.equals(String.valueOf(payload.get("r")));
            request.setAttribute(ATTR_USER_ID, userId);
            request.setAttribute(ATTR_ROLE, payload.get("r"));
            request.setAttribute(ATTR_USERNAME, payload.get("u"));
        }

        String path = request.getRequestURI();

        // 第二步：按路由规则决定要不要拦
        if (needsAdmin(path)) {
            if (payload == null) {
                return reject(response, 401, "请先登录（缺少或无效的 Authorization: Bearer <token>）");
            }
            if (!admin) {
                return reject(response, 403, "该功能仅管理员可用，当前角色：" + payload.get("r"));
            }
            return true;
        }

        if (needsLogin(path) && userId == null) {
            return reject(response, 401, "请先登录");
        }
        return true;
    }

    private boolean needsAdmin(String path) {
        return ADMIN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean needsLogin(String path) {
        return LOGIN_REQUIRED_PREFIXES.stream().anyMatch(path::startsWith);
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
