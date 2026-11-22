package com.campus.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static javax.swing.DropMode.ON;

@Repository
public class DocumentDao {

    private final JdbcTemplate jdbc;

    public DocumentDao(JdbcTemplate jdbc) {   // Spring 自动注入，连接池它管
        this.jdbc = jdbc;
    }

    /** 插入一条记录，返回自增 id。 */
    public long insert(String originalName, String storedName, String fileType, long fileSize) {
        String title = originalName.replaceFirst("(?i)\\.[^.]+$", "");   // 去掉 .pdf 当标题

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kb_document(title, original_name, stored_name, file_type, file_size, status) " +
                            "VALUES (?, ?, ?, ?, ?, 0)",
                    new String[]{"id"});
            ps.setString(1, title);
            ps.setString(2, originalName);
            ps.setString(3, storedName);
            ps.setString(4, fileType);
            ps.setLong(5, fileSize);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 分页查询，新的在前。 */
    public List<Map<String, Object>> page(int offset, int size) {
        return jdbc.queryForList(
                "SELECT id, title, original_name, file_type, file_size, status, chunk_count, create_time " +
                        "FROM kb_document ORDER BY id DESC LIMIT ? OFFSET ?", size, offset);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Long.class);
        return n == null ? 0 : n;
    }

    /** 按 id 查一条文档（重新处理时要用 stored_name 去磁盘读原文件）。查不到返回 null。 */
    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, original_name, stored_name, file_type, file_size, status, chunk_count " +
                        "FROM kb_document WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 需要（重新）入库的文档 id：没处理成功的、或者一个块都没有的。 */
    public List<Long> findIdsNeedingIngest() {
        return jdbc.queryForList(
                "SELECT id FROM kb_document WHERE status <> 2 OR chunk_count = 0 ORDER BY id", Long.class);
    }

    /** 全部文档 id（force 重跑时用：哪怕上次成功了也重跑）。 */
    public List<Long> findAllIds() {
        return jdbc.queryForList("SELECT id FROM kb_document ORDER BY id", Long.class);
    }

    // ==================== 下面开始处理"块"（kb_chunk） ====================

    /** 更新处理状态：0 待处理 / 1 处理中 / 2 成功 / 3 失败。 */
    public void updateStatus(long documentId, int status, int chunkCount, String failReason) {
        jdbc.update("UPDATE kb_document SET status = ?, chunk_count = ?, fail_reason = ? WHERE id = ?",
                status, chunkCount, failReason, documentId);
    }

    /** 重新切分前，先把这个文档旧的所有块删掉（否则会重复）。 */
    public void deleteChunks(long documentId) {
        jdbc.update("DELETE FROM kb_chunk WHERE document_id = ?", documentId);
    }

    /** 插入一块文本。chunk_index 从 0 开始，表示这块在原文里的顺序。 */
    public void insertChunk(long documentId, int chunkIndex, String content) {
        jdbc.update("INSERT INTO kb_chunk(document_id, chunk_index, content, char_count) VALUES (?, ?, ?, ?)",
                documentId, chunkIndex, content, content.length());
    }

    /** 查某个文档的块，按顺序。limit 防止一次拉太多。 */
    public List<Map<String, Object>> listChunks(long documentId, int limit) {
        return jdbc.queryForList(
                "SELECT id, chunk_index, char_count, content FROM kb_chunk WHERE document_id = ? " +
                        "ORDER BY chunk_index LIMIT ?", documentId, limit);
    }

    public long countChunks() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk", Long.class);
        return n == null ? 0 : n;
    }

    // ==================== 向量化相关 ====================

    /** 一块"待向量化"的数据：只要 id 和内容。 */
    public record ChunkForEmbed(long id, String content) {
    }

    /** 还没算向量的块（embedding 列为空）。limit 防止一次拉太多把内存撑爆。 */
    public List<ChunkForEmbed> listChunksNeedingEmbedding(int limit) {
        return jdbc.query(
                "SELECT id, content FROM kb_chunk WHERE embedding IS NULL ORDER BY id LIMIT ?",
                (rs, rowNum) -> new ChunkForEmbed(rs.getLong("id"), rs.getString("content")),
                limit);
    }

    /** 把算好的向量写回块里，同时记下是哪个模型、多少维。 */
    public void updateChunkEmbedding(long chunkId, String embeddingJson, String model, int dim) {
        jdbc.update("UPDATE kb_chunk SET embedding = ?, embedding_model = ?, embedding_dim = ? WHERE id = ?",
                embeddingJson, model, dim, chunkId);
    }

    public long countEmbeddedChunks() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk WHERE embedding IS NOT NULL", Long.class);
        return n == null ? 0 : n;
    }
    /** 一次把有向量的块全查出来（含文档标题，联表） */


    public record ChunkWithEmbedding(
            long chunkId,
            String documentTitle,
            int chunkIndex,
            String content,
            String embedding) {
    }
    public List<ChunkWithEmbedding> listChunksWithEmbedding() {
        // """ ... """ 是 Java 15 的"文本块"，可以写成多行字符串，不用拼 + 号
        String sql = """
            SELECT c.id, d.title, c.chunk_index, c.content, c.embedding
            FROM kb_chunk c
            JOIN kb_document d ON d.id = c.document_id
            WHERE c.embedding IS NOT NULL
            ORDER BY c.id
            """;

        // jdbc.query 会把下面这个 lambda 对每一行调用一次
        return jdbc.query(sql, (rs, rowNum) -> new ChunkWithEmbedding(
                rs.getLong("id"),                // 第 1 个参数：chunkId
                rs.getString("title"),           // 第 2 个参数：documentTitle
                rs.getInt("chunk_index"),        // 第 3 个参数：chunkIndex
                rs.getString("content"),         // 第 4 个参数：content
                rs.getString("embedding")        // 第 5 个参数：embedding
        ));

    }

}