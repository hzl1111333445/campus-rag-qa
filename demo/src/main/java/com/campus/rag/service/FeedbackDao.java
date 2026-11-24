package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答反馈的数据库访问（qa_feedback）。
 *
 * <p>为什么单独一个 DAO：反馈是"用户行为"，和会话/消息（系统产出）的生命周期不同，
 * 删会话时不应该连统计一起丢；以后要做数据保留策略也只改这里。
 *
 * <p>四条设计决定（写在这里，答辩时直接能讲）：
 * <ol>
 *   <li><b>三档解决程度，不是赞/踩二元</b>：2=完全解决、1=部分解决、0=未解决。
 *       原因很实际——"回答不完整"不等于"没用"，用户拿到一半信息也算有收获；
 *       二元评价会把这类情况强行算成差评，既不诚实，也会把优化方向带偏。</li>
 *   <li><b>缺陷与解决程度分开存</b>：{@code problems} 是多选（逗号分隔），只在 level&lt;2 时收集。
 *       把"缺什么"和"有没有用"混成一个字段，后面就没法回答"哪类缺陷最伤用户"。</li>
 *   <li>唯一键 (message_id, user_id)：同一个人对同一条回答只保留一条评价，重复提交走 UPDATE，
 *       这样"解决率 = 完全解决数 / 被评价回答数"的分母才干净，也防刷票。</li>
 *   <li>查询都带 LIMIT：管理端列表可能很长，宁可分页也不一次性拉全表。</li>
 * </ol>
 */
@Repository
public class FeedbackDao {

    /** 完全解决：用户的问题被答全了。 */
    public static final int LEVEL_SOLVED = 2;
    /** 部分解决：拿到了有用信息，但还差细节。 */
    public static final int LEVEL_PARTIAL = 1;
    /** 未解决：答偏了、没依据、或者干脆拒答但用户觉得该能答。 */
    public static final int LEVEL_UNSOLVED = 0;

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
    public void upsert(long messageId, long userId, int level, String problems, String comment) {
        jdbc.update("""
                INSERT INTO qa_feedback(message_id, user_id, level, problems, comment)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    level   = VALUES(level),
                    problems = VALUES(problems),
                    comment = VALUES(comment),
                    update_time = CURRENT_TIMESTAMP
                """, messageId, userId, level, problems, comment);
    }

    /** 我自己的评价（前端打开历史会话时回显"已评价 + 选过的缺陷"）。 */
    public Map<String, Object> findMine(long messageId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT message_id, level, problems, comment, update_time
                FROM qa_feedback WHERE message_id = ? AND user_id = ?
                """, messageId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int count(Integer level) {
        if (level == null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM qa_feedback", Integer.class);
            return n == null ? 0 : n;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM qa_feedback WHERE level = ?",
                Integer.class, level);
        return n == null ? 0 : n;
    }

    /**
     * 管理端列表：一条评价 + 它对应的提问 + 回答摘要 + 评价人。
     *
     * <p>提问怎么找？assistant 消息的上一条同会话消息就是提问（子查询取 MAX(id)）。
     * 用 LEFT JOIN 而不是 JOIN：提问被清理掉时也要能显示这条评价。
     */
    public List<Map<String, Object>> page(Integer level, int limit, int offset) {
        String where = level == null ? "" : " WHERE f.level = ? ";
        String sql = """
                SELECT f.id, f.message_id, f.level, f.problems, f.comment, f.create_time,
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
        return level == null
                ? jdbc.queryForList(sql, limit, offset)
                : jdbc.queryForList(sql, level, limit, offset);
    }

    /**
     * 运营统计。
     *
     * <p>为什么给三个比率而不是一个"满意度"：三个比率分别回答三个不同问题——
     * 解决率（用户到底有没有被解决）、有帮助率（含部分解决，衡量"至少没白问"）、
     * 未解决率（当前最该修的池子）。只报一个数会把"部分解决"藏起来。
     */
    public Map<String, Object> stats(int days) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN level = 2 THEN 1 ELSE 0 END) AS solved,
                       SUM(CASE WHEN level = 1 THEN 1 ELSE 0 END) AS partial,
                       SUM(CASE WHEN level = 0 THEN 1 ELSE 0 END) AS unsolved
                FROM qa_feedback
                """);
        long total = num(row.get("total"));
        long solved = num(row.get("solved"));
        long partial = num(row.get("partial"));
        long unsolved = num(row.get("unsolved"));
        out.put("total", total);
        out.put("solved", solved);
        out.put("partial", partial);
        out.put("unsolved", unsolved);
        out.put("solvedRate", rate(solved, total));
        out.put("helpfulRate", rate(solved + partial, total));
        out.put("unsolvedRate", rate(unsolved, total));

        // 缺陷分布：problems 是逗号分隔的多选，SQL 里不好拆，取回来在 Java 里数
        List<String> problemRows = jdbc.queryForList("""
                SELECT problems FROM qa_feedback
                WHERE level < 2 AND problems IS NOT NULL AND problems <> ''
                  AND create_time >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                """, String.class, days);
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String s : problemRows) {
            for (String p : s.split("[,，]")) {
                String key = p.trim();
                if (!key.isEmpty()) {
                    counter.merge(key, 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> byProblem = new ArrayList<>();
        counter.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> byProblem.add(Map.of("problem", e.getKey(), "n", e.getValue())));
        out.put("byProblem", byProblem);

        out.put("byDay", jdbc.queryForList("""
                SELECT DATE(create_time) AS day,
                       SUM(CASE WHEN level = 2 THEN 1 ELSE 0 END) AS solved,
                       SUM(CASE WHEN level = 1 THEN 1 ELSE 0 END) AS partial,
                       SUM(CASE WHEN level = 0 THEN 1 ELSE 0 END) AS unsolved
                FROM qa_feedback
                WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                GROUP BY DATE(create_time) ORDER BY day
                """, days));

        out.put("recentUnresolved", jdbc.queryForList("""
                SELECT f.id, f.message_id, f.level, f.problems, f.comment, f.create_time,
                       q.content AS question
                FROM qa_feedback f
                JOIN qa_message m ON m.id = f.message_id
                LEFT JOIN qa_message q ON q.id = (
                    SELECT MAX(x.id) FROM qa_message x
                    WHERE x.session_id = m.session_id AND x.id < m.id
                )
                WHERE f.level < 2
                ORDER BY f.create_time DESC, f.id DESC LIMIT 5
                """));
        return out;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Double rate(long part, long total) {
        if (total == 0) {
            return null;
        }
        return Math.round((double) part / total * 1000) / 10.0;
    }
}
