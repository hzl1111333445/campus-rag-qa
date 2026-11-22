package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * 会话与消息的数据库访问（qa_session / qa_message）。
 *
 * <p>为什么单独一个 DAO 而不塞进 {@link DocumentDao}？
 * 文档库和问答记录是两套东西：一个属于"知识"，一个属于"使用痕迹"，
 * 以后要做数据保留策略（比如消息只留 90 天）时只改这个类，不会碰到文档逻辑。
 */
@Repository
public class SessionDao {

    private final JdbcTemplate jdbc;

    public SessionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 会话 ====================

    /** 新建会话，返回自增 id。title 为空时给个默认名。 */
    public long create(String title) {
        String t = (title == null || title.isBlank()) ? "新的对话" : title.trim();
        if (t.length() > 100) {
            t = t.substring(0, 100);
        }
        String finalTitle = t;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO qa_session(title) VALUES (?)", new String[]{"id"});
            ps.setString(1, finalTitle);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 会话列表，最近提问的在前。带每条会话的消息数（前端要显示"N 条"）。 */
    public List<Map<String, Object>> list(int limit) {
        String sql = """
                SELECT s.id, s.title, s.create_time, s.update_time,
                       (SELECT COUNT(*) FROM qa_message m WHERE m.session_id = s.id) AS message_count
                FROM qa_session s
                ORDER BY s.update_time DESC, s.id DESC
                LIMIT ?
                """;
        return jdbc.queryForList(sql, limit);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, create_time, update_time FROM qa_session WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 改名。返回影响行数（0 表示这条会话不存在）。 */
    public int rename(long id, String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.length() > 100) {
            t = t.substring(0, 100);
        }
        return jdbc.update("UPDATE qa_session SET title = ? WHERE id = ?", t, id);
    }

    /** 删会话：先删消息再删会话，否则留下孤儿消息。 */
    public int delete(long id) {
        jdbc.update("DELETE FROM qa_message WHERE session_id = ?", id);
        return jdbc.update("DELETE FROM qa_session WHERE id = ?", id);
    }

    // ==================== 消息 ====================

    /**
     * 存一条消息。
     *
     * <p>assistant 那行会带上当次的校验信号与引用快照；user 那行这些字段传 null。
     * 用 setObject + Types 是为了能传 null（JdbcTemplate 的 varargs 对 null 不友好）。
     */
    public void insertMessage(long sessionId, String role, String content,
                              boolean gated, Boolean citationOk, Boolean grounded,
                              Integer factCount, Double topScore, Long elapsedMs,
                              String referencesJson, String missingFacts) {
        String sql = """
                INSERT INTO qa_message(
                    session_id, role, content, gated, citation_ok, grounded,
                    fact_count, top_score, elapsed_ms, references_json, missing_facts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(sql, (PreparedStatement ps) -> {
            ps.setLong(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setBoolean(4, gated);
            setNullableBool(ps, 5, citationOk);
            setNullableBool(ps, 6, grounded);
            setNullableInt(ps, 7, factCount);
            setNullableDouble(ps, 8, topScore);
            setNullableLong(ps, 9, elapsedMs);
            setNullableString(ps, 10, referencesJson);
            setNullableString(ps, 11, missingFacts);
        });
    }

    /** 某个会话的全部消息，按时间正序（前端就是从上往下渲染）。 */
    public List<Map<String, Object>> listMessages(long sessionId) {
        return jdbc.queryForList("""
                SELECT id, role, content, gated, citation_ok, grounded, fact_count,
                       top_score, elapsed_ms, references_json, missing_facts, create_time
                FROM qa_message
                WHERE session_id = ?
                ORDER BY id
                """, sessionId);
    }

    public long countBySession(long sessionId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_message WHERE session_id = ?", Long.class, sessionId);
        return n == null ? 0 : n;
    }

    private static void setNullableBool(PreparedStatement ps, int i, Boolean v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.BOOLEAN);
        } else {
            ps.setBoolean(i, v);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int i, Long v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static void setNullableString(PreparedStatement ps, int i, String v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i, v);
        }
    }
}
