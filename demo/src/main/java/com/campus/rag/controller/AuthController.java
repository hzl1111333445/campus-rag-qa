package com.campus.rag.controller;

import com.campus.rag.service.AuthException;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.TokenService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录相关接口。
 *
 * <p>{@code /api/auth/**} 不在权限拦截器的管理员清单里，所以登录页可以匿名访问。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    /** 登录：POST /api/auth/login {"username":"admin","password":"admin123"} */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return authService.login(body.get("username"), body.get("password"));
    }

    /**
     * 当前登录人是谁 —— 前端刷新页面后用它把用户信息"捞回来"，
     * 免得只靠 localStorage 里那份可能过期的副本。
     */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization == null ? null : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
        Map<String, Object> payload = tokenService.verify(token);
        if (payload == null) {
            throw new AuthException("未登录或登录已过期");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", payload.get("uid"));
        data.put("username", payload.get("u"));
        data.put("role", payload.get("r"));
        // 过期时间是秒，前端可以用它做过期前提醒
        data.put("exp", payload.get("exp"));
        return data;
    }
}
