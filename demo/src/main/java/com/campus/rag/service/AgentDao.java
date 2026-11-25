package com.campus.rag.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Agent 执行记录（agent_run / agent_step）。只写过程留痕，不碰业务表。
 */
@Repository
public class AgentDao {

    private final JdbcTemplate jdbc;

    public AgentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createRun(Long sessionId, Long userId, String question) {
        String sql = "INSERT INTO agent_run(session_id, user_id, question, status) VALUES (?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            if (sessionId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, sessionId);
            }
            if (userId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, userId);
            }
            ps.setString(3, cut(question, 1000));
            ps.setString(4, "RUNNING");
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void finishRun(long runId, String status, int steps, long totalMs, int tokenEst,
                          String finalAnswer, String citations, String error) {
        jdbc.update("""
                UPDATE agent_run
                   SET status=?, steps=?, total_ms=?, token_est=?, final_answer=?, citations=?, error=?
                 WHERE id=?
                """, status, steps, totalMs, tokenEst, finalAnswer, cut(citations, 500), cut(error, 500), runId);
    }

    public void appendStep(long runId, int stepNo, String thought, String toolName,
                           String argsJson, String observation, boolean ok, long latencyMs) {
        jdbc.update("""
                INSERT INTO agent_step(run_id, step_no, thought, tool_name, args_json, observation, ok, latency_ms)
                VALUES (?,?,?,?,?,?,?,?)
                """, runId, stepNo, cut(thought, 1000), cut(toolName, 64), cut(argsJson, 2000),
                observation, ok ? 1 : 0, latencyMs);
    }

    public Map<String, Object> findRun(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM agent_run WHERE id=?", runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listSteps(long runId) {
        return jdbc.queryForList("SELECT * FROM agent_step WHERE run_id=? ORDER BY step_no", runId);
    }

    public List<Map<String, Object>> listRuns(int limit, Long userId) {
        if (userId == null) {
            return jdbc.queryForList("SELECT * FROM agent_run ORDER BY id DESC LIMIT ?", limit);
        }
        return jdbc.queryForList("SELECT * FROM agent_run WHERE user_id=? ORDER BY id DESC LIMIT ?", userId, limit);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}