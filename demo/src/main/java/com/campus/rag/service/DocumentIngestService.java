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
    /** 给每个块拼「文档标题 + 章节名」前缀，解决"块里没标题导致检索不到"。 */
    private final ChunkEnricher chunkEnricher;

    /** 每块目标字符数。放配置里是为了以后做"切分参数对比实验"时能改。 */
    private final int chunkSize;
    /** 相邻块重叠字符数。 */
    private final int chunkOverlap;

    /** 只负责"写库这一小段"的原子操作（@Transactional 在它身上，才能被代理到）。 */
    private final DocumentChunkWriter chunkWriter;

    public DocumentIngestService(DocumentParser parser,
                                 DocumentDao documentDao,
                                 DocumentStorage storage,
                                 ChunkEnricher chunkEnricher,
                                 DocumentChunkWriter chunkWriter,
                                 @Value("${campus.split.size:220}") int chunkSize,
                                 @Value("${campus.split.overlap:50}") int chunkOverlap) {
        this.parser = parser;
        this.documentDao = documentDao;
        this.chunkWriter = chunkWriter;
        this.storage = storage;
        this.chunkEnricher = chunkEnricher;
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

            // 关键一步：给每个块拼上「文档标题 + 章节名」。
            // 必须在入库前做——向量是在入库时算的，检索时再拼就晚了。
            String title = titleOf(documentId, fileName);
            chunks = chunkEnricher.enrich(title, chunks);

            // 写库这一小段是原子的：删旧块 + 批量插新块 + 更新状态，要么全成、要么一块不动。
            // 为什么把解析/切分留在事务外：11 份扫描件解析要秒级，放事务里会长时间持锁；
            // 而且 @Transactional 必须由外部 bean 调用才生效（同类自调不代理）。
            chunkWriter.replaceChunks(documentId, chunks);

            System.out.printf("[ingest] 文档 %d 处理完成：%d 字 → %d 块（size=%d, overlap=%d），耗时 %d ms%n",
                    documentId, text.length(), chunks.size(), chunkSize, chunkOverlap,
                    System.currentTimeMillis() - begin);
            return chunks.size();

        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (reason.length() > 480) {
                reason = reason.substring(0, 480);   // 字段是 varchar(500)，截断防止插入失败
            }
            // 上一个事务已回滚；失败状态 + 重试次数用独立事务写，否则用户会永远看到"处理中"
            chunkWriter.markFailed(documentId, reason);
            System.out.println("[ingest] 文档 " + documentId + " 处理失败：" + reason);
            return 0;
        }
    }

    /**
     * 取文档标题（拼块头前缀用）。
     *
     * <p>优先用数据库里的 title（用户在页面上可能改过标题），
     * 拿不到才退化成文件名去掉扩展名——所以这里不给异常，只做兜底。
     */
    private String titleOf(long documentId, String fileName) {
        Map<String, Object> row = documentDao.findById(documentId);
        if (row != null && row.get("title") != null) {
            String t = String.valueOf(row.get("title")).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return fileName == null ? "" : fileName.replaceFirst("(?i)\\.[^.]+$", "");
    }
}
