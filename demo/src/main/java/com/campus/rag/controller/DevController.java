package com.campus.rag.controller;

import com.campus.rag.service.DocumentDao;
import com.campus.rag.service.DocumentIngestService;
import com.campus.rag.service.EmbeddingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发/运维用的接口，不属于业务功能。功能跑通以后这些接口会被删掉或加权限。
 */
@RestController
@RequestMapping("/api/dev")
public class DevController {

    private final DocumentIngestService ingestService;
    private final DocumentDao documentDao;
    private final EmbeddingService embeddingService;

    public DevController(DocumentIngestService ingestService,
                         DocumentDao documentDao,
                         EmbeddingService embeddingService) {
        this.ingestService = ingestService;
        this.documentDao = documentDao;
        this.embeddingService = embeddingService;
    }

    /**
     * 把文档重跑一遍"解析 → 切分 → 入库"。改了切分参数后用这个。
     *
     * @param force true = 不管上次成功与否，全部重跑；false = 只跑没成功过的
     */
    @PostMapping("/reingest-all")
    public Map<String, Object> reingestAll(@RequestParam(defaultValue = "false") boolean force) {
        long begin = System.currentTimeMillis();
        List<Long> ids = force ? documentDao.findAllIds() : documentDao.findIdsNeedingIngest();

        int ok = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (Long id : ids) {
            int chunkCount = ingestService.reingest(id);
            if (chunkCount > 0) {
                ok++;
            } else {
                fail++;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", id);
            one.put("chunkCount", chunkCount);
            details.add(one);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidates", ids.size());
        data.put("success", ok);
        data.put("failed", fail);
        data.put("chunkTotal", documentDao.countChunks());
        data.put("elapsedMs", System.currentTimeMillis() - begin);
        data.put("details", details);
        return data;
    }

    /**
     * 把还没有向量的块批量向量化。
     *
     * @param maxChunks 本次最多处理多少块（第一次调试建议先传 10，跑 2 秒就能看到结果）
     */
    @PostMapping("/embed-all")
    public Map<String, Object> embedAll(@RequestParam(defaultValue = "1000") int maxChunks) {
        return embeddingService.embedPending(maxChunks);
    }
}
