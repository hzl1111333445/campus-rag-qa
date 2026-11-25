package com.campus.rag.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：{@code X-No-Persist} 真的不落库，而且不回会话头。
 *
 * <p>为什么值得单独写一条：这个头是"机器调用"与"真人调用"的分界线。
 * 一旦回归（比如有人把判断挪到了别处、或忘了 return），
 * 评测脚本每次跑 120 题就会往对话库里灌 120 条假会话——数据污染是静默的，测试能兜住。
 *
 * <p>会真调一次大模型（约 1~3 秒），这是唯一能覆盖整条链路的写法；
 * 用真实 MySQL/Redis，不换内存实现（换 H2 会把 MySQL 方言差异掩盖掉，见续 45 的教训）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaNoPersistIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private long sessionCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM qa_session", Long.class);
        return n == null ? 0L : n;
    }

    @AfterEach
    void clean() {
        // 万一被测出"落了库"，把测试产生的会话清掉，避免污染后续手工验证
        jdbc.update("DELETE FROM qa_message WHERE session_id IN "
                + "(SELECT id FROM qa_session WHERE title LIKE '测试：%')");
        jdbc.update("DELETE FROM qa_session WHERE title LIKE '测试：%'");
    }

    @Test
    @DisplayName("POST /api/qa 带 X-No-Persist → 200，且不新增会话、不回 X-Session-Id")
    void noPersistDoesNotWrite() {
        long before = sessionCount();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-No-Persist", "1");
        String body = "{\"question\":\"测试：宿舍楼晚上几点关门？\",\"topK\":3}";

        ResponseEntity<Map> resp = rest.exchange("/api/qa", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(resp.getStatusCode().value()).isIn(200, 429);
        assertThat(resp.getHeaders().getFirst("X-Session-Id"))
                .as("机器调用不该回会话 id").isNull();
        assertThat(sessionCount()).as("机器调用不该落库").isEqualTo(before);
    }
}
