package com.campus.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档入库服务：把"字节"变成"数据库里的若干个文本块"。
 *
 * <p>完整链条：<b>解析 → 切分 → 先删旧块 → 逐块入库 → 更新状态</b>。
 *
 * <p>为什么单独一个类，而不是写在 Controller 里？
 * Controller 的职责是"接请求、返响应"；这里干的是"业务处理"。分开之后：
 * 以后要做异步处理（上传立刻返回，后台慢慢切）、要重跑、要写测试，都不用动接口层。
 */
@Service
public class DocumentIngestService {

    private final DocumentParser parser;
    private final DocumentDao documentDao;
    private final DocumentStorage storage;

    /** 每块目标字符数。放配置里是为了以后做"切分参数对比实验"时能改。 */
    private final int chunkSize;
    /** 相邻块重叠字符数。 */
    private final int chunkOverlap;

    public DocumentIngestService(DocumentParser parser,
                                 DocumentDao documentDao,
                                 DocumentStorage storage,
                                 @Value("${campus.split.size:220}") int chunkSize,
                                 @Value("${campus.split.overlap:50}") int chunkOverlap) {
        this.parser = parser;
        this.documentDao = documentDao;
        this.storage = storage;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 对一条"已经上传过"的文档重新跑一遍入库（磁盘上还留着原文件，不用用户重传）。
     * 改了切分参数、或当初上传时还没这条流水线，都用它。
     */
    public int reingest(long documentId) {
        Map<String, Object> row = documentDao.findById(documentId);
        if (row == null) {
            throw new IllegalArgumentException("文档不存在：" + documentId);
        }
        try {
            byte[] bytes = storage.read((String) row.get("stored_name"));
            return ingest(documentId, bytes, (String) row.get("original_name"));
        } catch (IOException e) {
            String reason = "读原文件失败：" + e.getMessage();
            documentDao.updateStatus(documentId, 3, 0, reason);
            System.out.println("[ingest] 文档 " + documentId + " " + reason);
            return 0;
        }
    }

    /**
     * 处理一个已经存盘的文档。
     *
     * <p>处理失败不会抛异常给调用方（上传本身已经成功了，文件在磁盘上躺着），
     * 而是把状态标成 3 失败，并记下原因，让前端能看到。
     *
     * @return 块数；失败时返回 0
     */
    public int ingest(long documentId, byte[] bytes, String fileName) {
        long begin = System.currentTimeMillis();
        try {
            String text = parser.parse(bytes, fileName);
            List<String> chunks = TextSplitter.split(text, chunkSize, chunkOverlap);

            // 先删旧块再插新块：这样同一个文件重复上传/重新切分不会留垃圾
            documentDao.deleteChunks(documentId);

            for (int i = 0; i < chunks.size(); i++) {
                documentDao.insertChunk(documentId, i, chunks.get(i));
            }

            documentDao.updateStatus(documentId, 2, chunks.size(), null);

            System.out.printf("[ingest] 文档 %d 处理完成：%d 字 → %d 块（size=%d, overlap=%d），耗时 %d ms%n",
                    documentId, text.length(), chunks.size(), chunkSize, chunkOverlap,
                    System.currentTimeMillis() - begin);
            return chunks.size();

        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (reason.length() > 480) {
                reason = reason.substring(0, 480);   // 字段是 varchar(500)，截断防止插入失败
            }
            documentDao.updateStatus(documentId, 3, 0, reason);
            System.out.println("[ingest] 文档 " + documentId + " 处理失败：" + reason);
            return 0;
        }
    }
}
