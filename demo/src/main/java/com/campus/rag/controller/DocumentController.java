package com.campus.rag.controller;

import com.campus.rag.service.DocumentDao;
import com.campus.rag.service.DocumentIngestService;
import com.campus.rag.service.DocumentStorage;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/document")
public class DocumentController {

    private final DocumentStorage storage;
    private final DocumentDao documentDao;
    private final DocumentIngestService ingestService;

    public DocumentController(DocumentStorage storage,
                              DocumentDao documentDao,
                              DocumentIngestService ingestService) {
        this.storage = storage;
        this.documentDao = documentDao;
        this.ingestService = ingestService;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }

        String originalName = file.getOriginalFilename();
        byte[] bytes = file.getBytes();

        // 第一步：存盘（文件本体）
        String storedName = storage.save(originalName, bytes);

        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        String fileType = dot >= 0 ? originalName.substring(dot + 1).toLowerCase() : "unknown";

        // 第二步：入库一条文档记录（状态 0 = 待处理）
        long id = documentDao.insert(originalName, storedName, fileType, file.getSize());

        // 第三步：解析 → 切分 → 把块写进 kb_chunk（失败会标状态 3，不抛异常）
        long begin = System.currentTimeMillis();
        int chunkCount = ingestService.ingest(id, bytes, originalName);
        long elapsed = System.currentTimeMillis() - begin;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("originalName", originalName);
        data.put("fileType", fileType);
        data.put("size", file.getSize());
        data.put("chunkCount", chunkCount);
        data.put("status", chunkCount > 0 ? 2 : 3);
        data.put("statusText", chunkCount > 0 ? "成功" : "失败");
        data.put("elapsedMs", elapsed);

        if (chunkCount == 0) {
            data.put("failReason", "解析或切分失败，详见 kb_document.fail_reason");
        }
        return data;
    }

    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", documentDao.page((safePage - 1) * safeSize, safeSize));
        data.put("total", documentDao.count());
        data.put("chunkTotal", documentDao.countChunks());
        data.put("page", safePage);
        data.put("size", safeSize);
        return data;
    }

    /** 看一个文档被切成了哪些块 —— 这一步最重要，能把"黑箱"打开给人看。 */
    @GetMapping("/{id}/chunks")
    public Map<String, Object> chunks(@PathVariable long id,
                                      @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", id);
        data.put("limit", safeLimit);
        data.put("chunks", documentDao.listChunks(id, safeLimit));
        return data;
    }

    /** 重新处理：磁盘上还有原文件，改了切分参数后点它，不用重新上传。 */
    @PostMapping("/{id}/reprocess")
    public Map<String, Object> reprocess(@PathVariable long id) {
        long begin = System.currentTimeMillis();
        int chunkCount = ingestService.reingest(id);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("chunkCount", chunkCount);
        data.put("status", chunkCount > 0 ? 2 : 3);
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        return data;
    }

    /**
     * 删除一份文档：先删块，再删记录，最后删磁盘原文件。
     *
     * <p>为什么顺序不能反：kb_chunk 靠 document_id 关联，记录先删掉就再也找不到这些块了，
     * 会留下永远没人认领的"孤儿块"，检索时还会被捞出来。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Map<String, Object> doc = documentDao.findById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在：" + id);
        }

        documentDao.deleteChunks(id);
        int deleted = documentDao.deleteById(id);

        // 磁盘文件删除失败不影响数据库一致性，只记录一下
        String storedName = String.valueOf(doc.get("stored_name"));
        boolean fileRemoved = false;
        try {
            fileRemoved = java.nio.file.Files.deleteIfExists(storage.dir().resolve(storedName));
        } catch (IOException e) {
            fileRemoved = false;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("deleted", deleted);
        data.put("fileRemoved", fileRemoved);
        return data;
    }
}
