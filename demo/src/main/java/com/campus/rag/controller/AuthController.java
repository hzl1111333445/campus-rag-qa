package com.campus.rag.controller;

import com.campus.rag.service.AuthException;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /**
     * 登录请求体。用户名/密码的格式不在这里判（判错密码是 401 不是 400），
     * 只保证"不是空字符串"——空字符串连查库都不用查，属于参数错误。
     */
    public record LoginRequest(
            @NotBlank(message = "不能为空") String username,
            @NotBlank(message = "不能为空") String password) {
    }

    /** 注册请求体。用户名/密码规则与 AuthService 保持一致，这里先挡一层，库不用查。 */
    public record RegisterRequest(
            @NotBlank(message = "不能为空")
            @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "只能是 3~20 位字母/数字/下划线")
            String username,
            @NotBlank(message = "不能为空")
            @Size(min = 6, max = 32, message = "长度需在 6~32 位之间")
            String password,
            @Size(max = 20, message = "最多 20 个字符")
            String displayName) {
    }

    /** 登录：POST /api/auth/login {"username":"admin","password":"admin123"} */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body) {
        return authService.login(body.username(), body.password());
    }

    /**
     * 注册：POST /api/auth/register {"username":"zhangsan","password":"123456","displayName":"张三"}
     *
     * <p>只允许注册成学生（角色不接受前端传参），并且注册成功就直接返回 token——
     * 少一次登录输入，也避免"注册成功却登不上"的困惑。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest body) {
        return authService.register(body.username(), body.password(), body.displayName());
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
