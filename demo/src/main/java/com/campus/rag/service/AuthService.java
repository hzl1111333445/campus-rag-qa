package com.campus.rag.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录与密码校验。
 *
 * <p>密码存法：每个用户一个随机 salt，库里只存 {@code sha256(salt + 明文)}。
 * 为什么不直接存明文：一旦库被拖走，明文密码会被拿去撞别的网站。
 * 为什么加 salt：不加的话同样的密码哈希相同，一眼能看出"这两个人密码一样"，
 * 也能用彩虹表反查。
 *
 * <p><b>生产上应该用 BCrypt / Argon2</b>（自带慢哈希与工作因子）。SHA-256 的问题是太快，
 * 拿到库可以暴力跑几百亿次；这里为了零依赖与可讲清楚原理而简化，风险点明写在此。
 */
@Service
public class AuthService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_STUDENT = "STUDENT";

    private final UserDao userDao;
    private final TokenService tokenService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserDao userDao, TokenService tokenService) {
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /**
     * 登录。成功返回 token + 用户信息；失败抛 IllegalArgumentException（由全局异常转 400/401）。
     */
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new AuthException("用户名和密码都不能为空");
        }
        Map<String, Object> user = userDao.findByUsername(username.trim());
        if (user == null) {
            // 提示统一为"用户名或密码错误"，不告诉对方到底是哪个错 —— 否则等于帮人枚举账号
            throw new AuthException("用户名或密码错误");
        }
        int status = user.get("status") == null ? 1 : ((Number) user.get("status")).intValue();
        if (status != 1) {
            throw new AuthException("账号已被禁用");
        }
        String salt = String.valueOf(user.get("salt"));
        String expected = String.valueOf(user.get("password_hash"));
        if (!expected.equals(hash(password, salt))) {
            throw new AuthException("用户名或密码错误");
        }

        long id = ((Number) user.get("id")).longValue();
        String name = String.valueOf(user.get("username"));
        String role = String.valueOf(user.get("role"));
        userDao.touchLogin(id);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", tokenService.issue(id, name, role));
        data.put("user", publicInfo(id, name, role, String.valueOf(user.get("display_name"))));
        return data;
    }

    /** 对外可见的用户信息：永远不含 salt 与 password_hash。 */
    public Map<String, Object> publicInfo(long id, String username, String role, String displayName) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("username", username);
        info.put("role", role);
        info.put("displayName", displayName);
        return info;
    }

    public String newSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败：" + e.getMessage(), e);
        }
    }

    /** 建账号用（初始化默认账号、以后做"新增管理员"都走它）。 */
    public void createUser(String username, String rawPassword, String role, String displayName) {
        String salt = newSalt();
        userDao.insert(username, hash(rawPassword, salt), salt, role, displayName);
    }
}
