package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.UserDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理（仅管理员）。
 *
 * <p>为什么单独一个 Controller 而不塞进 AuthController：语义不同。
 * {@code /api/auth/**} 是"谁能进来"（匿名可访问），{@code /api/user/**} 是"进来之后能管谁"
 * （必须管理员）。混在一起很容易哪天把管理员前缀加错地方。
 *
 * <p>两条硬约束（防"把自己锁在门外"）：
 * <ol>
 *   <li>不能停用自己</li>
 *   <li>不能停用最后一个启用中的管理员</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserDao userDao;
    private final AuthService authService;

    public UserController(UserDao userDao, AuthService authService) {
        this.userDao = userDao;
        this.authService = authService;
    }

    /** 分页列表：GET /api/user/page?page=1&size=10&keyword=张 */
    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String keyword) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        long total = userDao.count(keyword);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : userDao.page(keyword, (safePage - 1) * safeSize, safeSize)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("username", row.get("username"));
            item.put("displayName", row.get("display_name"));
            item.put("role", row.get("role"));
            item.put("status", row.get("status"));
            item.put("lastLogin", row.get("last_login"));
            item.put("createTime", row.get("create_time"));
            records.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("records", records);
        data.put("page", safePage);
        data.put("size", safeSize);
        return data;
    }

    /** 启停请求体：{"status":0}，只收 0/1。 */
    public record StatusRequest(
            @NotNull(message = "不能为空")
            @Min(value = 0, message = "只能是 0 或 1")
            @Max(value = 1, message = "只能是 0 或 1")
            Integer status) {
    }

    /** 重置密码请求体：{"password":"新密码"}。 */
    public record PasswordRequest(
            @NotBlank(message = "不能为空")
            @Size(min = 6, max = 32, message = "长度需在 6~32 位之间")
            String password) {
    }

    /** 启用 / 停用：PATCH /api/user/{id}/status {"status":0} */
    @PatchMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable long id,
                                            @Valid @RequestBody StatusRequest body,
                                            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long selfId) {
        int status = body.status();
        Map<String, Object> user = userDao.findById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在：" + id);
        }
        if (status == 0) {
            if (selfId != null && selfId == id) {
                // 停用自己会立刻失效所有令牌，属于自伤操作，直接拒绝
                throw new IllegalArgumentException("不能停用当前登录的账号");
            }
            boolean isAdmin = AuthService.ROLE_ADMIN.equals(String.valueOf(user.get("role")));
            if (isAdmin && userDao.countActiveAdmins() <= 1) {
                throw new IllegalArgumentException("这是最后一个启用中的管理员，停用后将没人能进后台");
            }
        }
        userDao.updateStatus(id, status);
        log.info("用户状态变更：id={} status={} by={}", id, status, selfId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", status);
        return data;
    }

    /** 重置密码：POST /api/user/{id}/reset-password {"password":"新密码"} */
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable long id, @Valid @RequestBody PasswordRequest body) {
        authService.resetPassword(id, body.password());
        log.info("管理员重置密码：id={}", id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("reset", true);
        return data;
    }

    private static int toInt(Object v, int dft) {
        try {
            return v == null ? dft : (int) Double.parseDouble(String.valueOf(v));
        } catch (RuntimeException e) {
            return dft;
        }
    }
}
