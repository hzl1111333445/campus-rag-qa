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
                "SELECT d.id, d.title, d.original_name, d.file_type, d.file_size, d.status, d.chunk_count, " +
                        "d.create_time, " +
                        // 顺手带出"还差多少块没向量"：管理员页要给每行显示向量化状态
                        "(SELECT COUNT(*) FROM kb_chunk c WHERE c.document_id = d.id AND c.embedding IS NULL) AS pending_chunks " +
                        "FROM kb_document d ORDER BY d.id DESC LIMIT ? OFFSET ?", size, offset);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 已成功入库的文档标题列表。
     *
     * <p>用途：喂给模型，让它知道"本知识库收录了哪些文件"。
     * 有了这份范围，它才能在答不上来时说出"目前未收录宿舍管理规定"，
     * 而不是含糊地说"学校没有这项规定"——后者会误导学生。
     */
    public List<String> listIngestedTitles() {
        return jdbc.queryForList(
                "SELECT title FROM kb_document WHERE status = 2 ORDER BY id", String.class);
    }

    /** 按 id 查一条文档（重新处理时要用 stored_name 去磁盘读原文件）。查不到返回 null。 */
    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, original_name, stored_name, file_type, file_size, status, chunk_count " +
                        "FROM kb_document WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 删除一条文档记录（块要先用 deleteChunks 删掉）。返回影响行数。 */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
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

    /** 引用回显用：按 chunkId 批量取"文档名 + 块号 + 正文"。 */
    public record ChunkDetail(long id, long documentId, String documentTitle, int chunkIndex, String content) {
    }

    /**
     * 按 id 批量取块详情。
     *
     * <p>只在 Agent 模式下用（把 allowedChunks 换成可展示的出处），
     * 所以不做全表扫描——IN 里最多十几个 id。
     */
    public List<ChunkDetail> findChunksByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) in.append(',');
            in.append('?');
        }
        String sql = "SELECT c.id, c.document_id, d.title, c.chunk_index, c.content "
                + "FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id "
                + "WHERE c.id IN (" + in + ")";
        return jdbc.query(sql, (rs, i) -> new ChunkDetail(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getString("title"),
                rs.getInt("chunk_index"),
                rs.getString("content")), ids.toArray());
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

    /**
     * 只取"某一份文档"里还没算向量的块 —— 上传完立刻补向量用。
     *
     * <p>为什么不直接调 listChunksNeedingEmbedding：那是全库扫，
     * 上传一份文档却可能顺带处理别人遗留的几百块，接口慢得莫名其妙。
     */
    public List<ChunkForEmbed> listChunksNeedingEmbeddingByDocument(long documentId, int limit) {
        return jdbc.query(
                "SELECT id, content FROM kb_chunk WHERE document_id = ? AND embedding IS NULL ORDER BY id LIMIT ?",
                (rs, rowNum) -> new ChunkForEmbed(rs.getLong("id"), rs.getString("content")),
                documentId, limit);
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