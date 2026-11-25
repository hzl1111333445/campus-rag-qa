package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 会话滚动摘要的存取（表 {@code qa_summary}，一个会话一条）。
 *
 * <p>为什么单独一个 DAO 而不是塞进 {@link SessionDao}：
 * 摘要是一个独立生命周期的东西（有覆盖位点、有自己的合并策略、将来可能换成向量记忆），
 * 与"会话/消息"的增删改查不是一类操作。分开后 {@code SessionDao} 不用为它多背 3 个方法。
 */
@Repository
public class SummaryDao {

    private final JdbcTemplate jdbc;

    public SummaryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读摘要；没有则返回 null。 */
    public Map<String, Object> find(long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id, upto_message_id, content, tokens, update_time "
                        + "FROM qa_summary WHERE session_id = ?", sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写入/更新摘要（一个会话一条，靠唯一键 upsert）。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再插或更新"：
     * 折叠是异步的，两次折叠可能并发，交给数据库的唯一键保证幂等更省事。
     */
    public void upsert(long sessionId, long uptoMessageId, String content, int tokens) {
        jdbc.update("""
                INSERT INTO qa_summary(session_id, upto_message_id, content, tokens)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    upto_message_id = VALUES(upto_message_id),
                    content = VALUES(content),
                    tokens = VALUES(tokens)
                """, sessionId, uptoMessageId, content, tokens);
    }

    /** 把已折叠的消息打标（原文仍保留，前端/排查仍能看到完整历史）。 */
    public int markFolded(long sessionId, long maxMessageId) {
        return jdbc.update(
                "UPDATE qa_message SET folded = 1 WHERE session_id = ? AND id <= ? AND folded = 0",
                sessionId, maxMessageId);
    }

    /** 删除某会话的摘要（会话被删时调用，避免孤儿行）。 */
    public int deleteBySession(long sessionId) {
        return jdbc.update("DELETE FROM qa_summary WHERE session_id = ?", sessionId);
    }
}
