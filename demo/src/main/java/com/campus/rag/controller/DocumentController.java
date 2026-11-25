package com.campus.rag.controller;

import com.campus.rag.service.DocumentDao;
import com.campus.rag.service.DocumentIngestService;
import com.campus.rag.service.DocumentStorage;
import com.campus.rag.service.EmbeddingService;
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
    private final EmbeddingService embeddingService;

    public DocumentController(DocumentStorage storage,
                              DocumentDao documentDao,
                              DocumentIngestService ingestService,
                              EmbeddingService embeddingService) {
        this.storage = storage;
        this.documentDao = documentDao;
        this.ingestService = ingestService;
        this.embeddingService = embeddingService;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件是空的");
        }

        String originalName = file.getOriginalFilename();
        byte[] bytes = file.getBytes();

        // 第 0 步：算内容指纹，先判重。
        // 为什么用内容 hash 而不是文件名：同一份文件改名再传是常事，按名字判重会漏。
        String fileHash = sha256Hex(bytes);
        Map<String, Object> existing = documentDao.findByHash(fileHash);
        if (existing != null) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("duplicate", true);
            dup.put("id", existing.get("id"));
            dup.put("originalName", existing.get("original_name"));
            dup.put("chunkCount", existing.get("chunk_count"));
            dup.put("message", "这个文件之前已经入库过（内容完全一致），直接复用，没有重复切分和向量化");
            return dup;
        }

        // 第一步：存盘（文件本体）
        String storedName = storage.save(originalName, bytes);

        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        String fileType = dot >= 0 ? originalName.substring(dot + 1).toLowerCase() : "unknown";

        // 第二步：入库一条文档记录（状态 0 = 待处理）
        long id = documentDao.insert(originalName, storedName, fileType, file.getSize(), fileHash);

        // 第三步：解析 → 切分 → 把块写进 kb_chunk（失败会标状态 3，不抛异常）
        long begin = System.currentTimeMillis();
        int chunkCount = ingestService.ingest(id, bytes, originalName);
        long elapsed = System.currentTimeMillis() - begin;

        // 第四步：立刻给这份文档的块补向量。
        // 不做这一步的话，新文档只有 BM25 能搜到、向量检索看不见它 —— 用户体感就是"传了但问不出来"。
        Map<String, Object> embed = chunkCount > 0
                ? embeddingService.embedPendingOfDocument(id, 500)
                : Map.of();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("originalName", originalName);
        data.put("fileType", fileType);
        data.put("size", file.getSize());
        data.put("chunkCount", chunkCount);
        data.put("status", chunkCount > 0 ? 2 : 3);
        data.put("statusText", chunkCount > 0 ? "成功" : "失败");
        data.put("embeddedThisRun", embed.getOrDefault("embeddedThisRun", 0));
        data.put("embedMs", embed.getOrDefault("elapsedMs", 0));
        if (embed.get("error") != null) {
            data.put("embedError", embed.get("error"));
        }
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

        Map<String, Object> embed = chunkCount > 0
                ? embeddingService.embedPendingOfDocument(id, 500)
                : Map.of();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("chunkCount", chunkCount);
        data.put("status", chunkCount > 0 ? 2 : 3);
        data.put("embeddedThisRun", embed.getOrDefault("embeddedThisRun", 0));
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        if (embed.get("error") != null) {
            data.put("embedError", embed.get("error"));
        }
        return data;
    }

    /**
     * 给"某一份"文档补向量：POST /api/document/{id}/embed
     *
     * <p>什么时候会用到：上传时向量化中途失败、或换了 embedding 模型要重算。
     * 与全局的 /api/dev/embed-all 的区别是**只处理这一份**，不会顺带把别的文档也跑一遍。
     */
    @PostMapping("/{id}/embed")
    public Map<String, Object> embed(@PathVariable long id) {
        Map<String, Object> doc = documentDao.findById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在：" + id);
        }
        Map<String, Object> embed = embeddingService.embedPendingOfDocument(id, 500);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("embeddedThisRun", embed.getOrDefault("embeddedThisRun", 0));
        data.put("pendingBefore", embed.getOrDefault("pendingBefore", 0));
        data.put("elapsedMs", embed.getOrDefault("elapsedMs", 0));
        if (embed.get("error") != null) {
            data.put("error", embed.get("error"));
        }
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

    /** 文件内容 SHA-256（十六进制小写）。用内容而不是文件名做幂等键。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会发生；真发生就让上传失败，而不是静默跳过幂等
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
