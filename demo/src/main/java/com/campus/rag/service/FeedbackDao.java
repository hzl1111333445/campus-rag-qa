package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答反馈的数据库访问（qa_feedback）。
 *
 * <p>为什么单独一个 DAO：反馈是"用户行为"，和会话/消息（系统产出）的生命周期不同，
 * 删会话时不应该连统计一起丢；以后要做数据保留策略也只改这里。
 *
 * <p>三条设计决定（写在这里，答辩时直接能讲）：
 * <ol>
 *   <li>唯一键 (message_id, user_id)：同一个人对同一条回答只保留一条评价，重复提交走 UPDATE，
 *       这样"满意度 = 好评数 / 被评价回答数"的分母才干净，也防刷票。</li>
 *   <li>reason 只存预设值（答非所问 / 没有依据 / 不完整 / 太啰嗦 / 其他）：自由文本没法聚合。</li>
 *   <li>查询都带 LIMIT：管理端列表可能很长，宁可分页也不一次性拉全表。</li>
 * </ol>
 */
@Repository
public class FeedbackDao {

    private final JdbcTemplate jdbc;

    public FeedbackDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 这条消息能不能被这个人评价。
     *
     * <p>只允许评价"自己会话里的 assistant 消息"——不能给别人的回答点赞，
     * 也不能给用户自己的提问点赞（那没有意义）。
     */
    public boolean canRate(long messageId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM qa_message m
                JOIN qa_session s ON s.id = m.session_id
                WHERE m.id = ? AND m.role = 'assistant' AND s.user_id = ?
                """, Integer.class, messageId, userId);
        return n != null && n > 0;
    }

    /** 评价（新增或改主意）。同一个人同一条消息只会有一行。 */
    public void upsert(long messageId, long userId, int rating, String reason, String comment) {
        jdbc.update("""
                INSERT INTO qa_feedback(message_id, user_id, rating, reason, comment)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    rating  = VALUES(rating),
                    reason  = VALUES(reason),
                    comment = VALUES(comment),
                    update_time = CURRENT_TIMESTAMP
                """, messageId, userId, rating, reason, comment);
    }

    /** 我自己的评价（前端打开历史会话时回显"已赞/已踩"）。 */
    public Map<String, Object> findMine(long messageId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT message_id, rating, reason, comment, update_time
                FROM qa_feedback WHERE message_id = ? AND user_id = ?
                """, messageId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int count(Integer rating) {
        if (rating == null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM qa_feedback", Integer.class);
            return n == null ? 0 : n;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM qa_feedback WHERE rating = ?",
                Integer.class, rating);
        return n == null ? 0 : n;
    }

    /**
     * 管理端列表：一条评价 + 它对应的提问 + 回答摘要 + 评价人。
     *
     * <p>提问怎么找？assistant 消息的上一条同会话消息就是提问（子查询取 MAX(id)）。
     * 用 LEFT JOIN 而不是 JOIN：提问被清理掉时也要能显示这条评价。
     */
    public List<Map<String, Object>> page(Integer rating, int limit, int offset) {
        String where = rating == null ? "" : " WHERE f.rating = ? ";
        String sql = """
                SELECT f.id, f.message_id, f.rating, f.reason, f.comment, f.create_time,
                       m.content AS answer_snippet,
                       q.content AS question,
                       u.username, u.display_name
                FROM qa_feedback f
                JOIN qa_message m ON m.id = f.message_id
                LEFT JOIN qa_message q ON q.id = (
                    SELECT MAX(x.id) FROM qa_message x
                    WHERE x.session_id = m.session_id AND x.id < m.id
                )
                LEFT JOIN sys_user u ON u.id = f.user_id
                """ + where + " ORDER BY f.create_time DESC, f.id DESC LIMIT ? OFFSET ?";
        return rating == null
                ? jdbc.queryForList(sql, limit, offset)
                : jdbc.queryForList(sql, rating, limit, offset);
    }

    /** 运营统计：总量、满意度、点踩原因分布、按天趋势。 */
    public Map<String, Object> stats(int days) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> total = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END)  AS good,
                       SUM(CASE WHEN rating = -1 THEN 1 ELSE 0 END) AS bad
                FROM qa_feedback
                """);
        long totalN = ((Number) total.getOrDefault("total", 0)).longValue();
        long good = total.get("good") == null ? 0 : ((Number) total.get("good")).longValue();
        long bad = total.get("bad") == null ? 0 : ((Number) total.get("bad")).longValue();
        out.put("total", totalN);
        out.put("good", good);
        out.put("bad", bad);
        out.put("satisfaction", totalN == 0 ? null : Math.round((double) good / totalN * 1000) / 10.0);

        out.put("byReason", jdbc.queryForList("""
                SELECT COALESCE(reason, '未填写') AS reason, COUNT(*) AS n
                FROM qa_feedback WHERE rating = -1
                GROUP BY reason ORDER BY n DESC
                """));
        out.put("byDay", jdbc.queryForList("""
                SELECT DATE(create_time) AS day,
                       COUNT(*) AS total,
                       SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END)  AS good,
                       SUM(CASE WHEN rating = -1 THEN 1 ELSE 0 END) AS bad
                FROM qa_feedback
                WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                GROUP BY DATE(create_time) ORDER BY day
                """, days));
        out.put("recentBad", jdbc.queryForList("""
                SELECT f.id, f.message_id, f.reason, f.comment, f.create_time,
                       q.content AS question
                FROM qa_feedback f
                JOIN qa_message m ON m.id = f.message_id
                LEFT JOIN qa_message q ON q.id = (
                    SELECT MAX(x.id) FROM qa_message x
                    WHERE x.session_id = m.session_id AND x.id < m.id
                )
                WHERE f.rating = -1
                ORDER BY f.create_time DESC, f.id DESC LIMIT 5
                """));
        return out;
    }
}
