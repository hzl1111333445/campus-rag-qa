package com.campus.rag.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 入库事务的回归测试：中途失败不能留下"半截块"。
 *
 * <p>两个刻意的选择：
 * <ol>
 *   <li><b>连真实 MySQL</b>（{@code Replace.NONE}）：本项目用了 MySQL 方言，
 *       换 H2 会把"其实不兼容"的 SQL 测成通过。</li>
 *   <li><b>测试方法自己不开事务</b>（{@code Propagation.NOT_SUPPORTED}）：
 *       如果测试框架开了事务，{@code @Transactional} 方法会**加入**外层事务，
 *       回滚要等到测试结束才发生——那样断言的其实是"没回滚"，测不出真问题。
 *       关掉外层事务，才能看到 writer 自己的事务真正回滚。代价是要自己清理数据。</li>
 * </ol>
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentDao.class, DocumentChunkWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentChunkWriterTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DocumentDao documentDao;

    @Autowired
    private DocumentChunkWriter writer;

    private final List<Long> created = new ArrayList<>();

    private long newDocument() {
        long id = documentDao.insert("测试文档.txt", "test-" + System.nanoTime() + ".txt", "txt", 12L, null);
        created.add(id);
        return id;
    }

    private int chunkCount(long documentId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk WHERE document_id = ?", Integer.class, documentId);
        return n == null ? 0 : n;
    }

    @AfterEach
    void cleanup() {
        for (Long id : created) {
            jdbc.update("DELETE FROM kb_chunk WHERE document_id = ?", id);
            jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
        }
        created.clear();
    }

    @Test
    @DisplayName("成功路径：删旧块 + 插新块 + 状态=2 一次完成")
    void replaceChunksWritesAll() {
        long id = newDocument();
        documentDao.insertChunkBatch(id, List.of("旧块A", "旧块B"));

        int written = writer.replaceChunks(id, List.of("新块1", "新块2", "新块3"));

        assertEquals(3, written);
        assertEquals(3, chunkCount(id), "旧块应被替换，只剩新的 3 块");
        Integer status = jdbc.queryForObject("SELECT status FROM kb_document WHERE id = ?", Integer.class, id);
        assertEquals(2, status, "成功后状态应为 2");
    }

    @Test
    @DisplayName("失败路径：第 2 块插入失败时，旧块必须原样保留（不能变半截）")
    void replaceChunksRollsBackOnFailure() {
        long id = newDocument();
        documentDao.insertChunkBatch(id, List.of("旧块A", "旧块B"));
        assertEquals(2, chunkCount(id));

        // content 列是 NOT NULL：传 null 会让第 2 条 INSERT 直接 SQL 异常
        List<String> bad = Arrays.asList("新块1", null);

        assertThrows(RuntimeException.class, () -> writer.replaceChunks(id, bad));
        assertEquals(2, chunkCount(id), "回滚后旧块应当仍在 —— 这就是 @Transactional 的价值");
    }

    @Test
    @DisplayName("失败后 markFailed 用独立事务写状态、原因与重试次数")
    void markFailedWritesReasonAndRetry() {
        long id = newDocument();
        writer.markFailed(id, "模拟失败：第 2 块插入失败");

        Integer status = jdbc.queryForObject("SELECT status FROM kb_document WHERE id = ?", Integer.class, id);
        Integer retry = jdbc.queryForObject("SELECT retry_count FROM kb_document WHERE id = ?", Integer.class, id);
        String reason = jdbc.queryForObject("SELECT fail_reason FROM kb_document WHERE id = ?", String.class, id);

        assertEquals(3, status);
        assertEquals(1, retry, "每失败一次重试次数 +1");
        assertEquals("模拟失败：第 2 块插入失败", reason);
    }
}
