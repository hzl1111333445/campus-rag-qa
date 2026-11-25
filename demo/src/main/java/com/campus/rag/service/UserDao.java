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

    /** 用户管理：分页查询（keyword 命中用户名或显示名）。 */
    public List<Map<String, Object>> page(String keyword, int offset, int size) {
        String like = keyword == null ? "" : keyword.trim();
        String sql = "SELECT id, username, role, display_name, status, last_login, create_time "
                + "FROM sys_user "
                + (like.isEmpty() ? "" : "WHERE username LIKE ? OR display_name LIKE ? ")
                + "ORDER BY id LIMIT ? OFFSET ?";
        if (like.isEmpty()) {
            return jdbc.queryForList(sql, size, offset);
        }
        String pattern = "%" + like + "%";
        return jdbc.queryForList(sql, pattern, pattern, size, offset);
    }

    public long count(String keyword) {
        String like = keyword == null ? "" : keyword.trim();
        if (like.isEmpty()) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
            return n == null ? 0 : n;
        }
        String pattern = "%" + like + "%";
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username LIKE ? OR display_name LIKE ?",
                Long.class, pattern, pattern);
        return n == null ? 0 : n;
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, role, display_name, status, last_login, create_time FROM sys_user WHERE id = ?",
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int updateStatus(long id, int status) {
        return jdbc.update("UPDATE sys_user SET status = ? WHERE id = ?", status, id);
    }

    public int updatePassword(long id, String passwordHash, String salt) {
        return jdbc.update("UPDATE sys_user SET password_hash = ?, salt = ? WHERE id = ?", passwordHash, salt, id);
    }

    /** 统计"启用中的管理员"数量：停用前要保证至少还剩一个，否则没人能进后台。 */
    public long countActiveAdmins() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN' AND status = 1", Long.class);
        return n == null ? 0 : n;
    }

    public void touchLogin(long id) {
        jdbc.update("UPDATE sys_user SET last_login = NOW() WHERE id = ?", id);
    }
}
