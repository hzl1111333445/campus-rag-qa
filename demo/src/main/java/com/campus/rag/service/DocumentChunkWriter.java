package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档块的"写入器"：只负责把一批块原子地替换进库。
 *
 * <p><b>为什么单独一个类？</b>因为 {@code @Transactional} 靠 Spring 代理生效，
 * <b>同一个类里自己调自己不会开事务</b>。解析和切分很慢（扫描件要秒级），
 * 不能包在事务里；所以必须让"慢的部分"留在 {@link DocumentIngestService}，
 * 把"写库这一小段"移到另一个 bean 上，由外部调用触发代理。
 *
 * <p>事务范围：<b>删旧块 + 批量插新块 + 更新状态</b>。
 * 要么三件事全成，要么一块都不动——避免"块删了一半、检索还能命中"的半截状态。
 */
@Service
public class DocumentChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkWriter.class);

    private final DocumentDao documentDao;

    public DocumentChunkWriter(DocumentDao documentDao) {
        this.documentDao = documentDao;
    }

    /**
     * 用新切出来的块替换该文档的全部旧块。
     *
     * @return 写入的块数
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceChunks(long documentId, List<String> chunks) {
        int size = chunks == null ? 0 : chunks.size();
        documentDao.deleteChunks(documentId);
        documentDao.insertChunkBatch(documentId, chunks);
        documentDao.updateStatus(documentId, 2, size, null);
        log.info("文档 {} 入库完成：{} 块（同一事务）", documentId, size);
        return size;
    }

    /**
     * 标记失败并累加重试次数。
     *
     * <p>注意它是**独立事务**：调用它时上一个事务已经回滚，否则这条状态更新也会被一起回滚，
     * 用户就永远看到"处理中"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(long documentId, String reason) {
        documentDao.deleteChunks(documentId);
        documentDao.updateStatus(documentId, 3, 0, reason);
        documentDao.increaseRetry(documentId);
    }
}
