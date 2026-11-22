package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 用户表（sys_user）的访问。
 *
 * <p>只做三件事：按用户名查、插入新用户、记录最后一次登录时间。
 * 注意查出来的 password_hash / salt 只在 AuthService 里用，绝不出现在接口响应里。
 */
@Repository
public class UserDao {

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findByUsername(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password_hash, salt, role, display_name, status FROM sys_user WHERE username = ?",
                username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int insert(String username, String passwordHash, String salt, String role, String displayName) {
        return jdbc.update(
                "INSERT INTO sys_user(username, password_hash, salt, role, display_name) VALUES (?, ?, ?, ?, ?)",
                username, passwordHash, salt, role, displayName);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        return n == null ? 0 : n;
    }

    public void touchLogin(long id) {
        jdbc.update("UPDATE sys_user SET last_login = NOW() WHERE id = ?", id);
    }
}
