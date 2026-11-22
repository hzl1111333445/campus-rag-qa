package com.campus.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tokenizer 和 Bm25Index 的单元测试。
 *
 * <p>为什么要写测试？因为 BM25 的分数是"一堆乘除"算出来的，
 * 改一个参数（K1/B）就可能把排序搞乱，靠肉眼看 JSON 根本发现不了。
 * 有了测试，重跑 3 秒就知道有没有改坏。
 */
class Bm25IndexTest {

    @Test
    void 分词应该输出单字加双字() {
        // "学生证" → 学、学生、生、生证、证
        assertEquals(List.of("学", "学生", "生", "生证", "证"), Tokenizer.tokenize("学生证"));
    }

    @Test
    void 清洗应该去掉标点和空格() {
        assertEquals("违纪处分", Tokenizer.clean("违纪、处分！"));
        assertEquals("abc123", Tokenizer.clean("ABC 123"));
    }

    @Test
    void 字面命中的块应该排在前面() {
        List<DocumentDao.ChunkWithEmbedding> chunks = List.of(
                new DocumentDao.ChunkWithEmbedding(1L, "公寓管理规定", 0,
                        "严禁使用大功率电器，违规者给予记过处分", ""),
                new DocumentDao.ChunkWithEmbedding(2L, "图书馆规定", 0,
                        "图书馆开放时间为七点半到二十二点", ""),
                new DocumentDao.ChunkWithEmbedding(3L, "学生证规定", 0,
                        "学生证丢失后到学生工作处补办", "")
        );
        Bm25Index index = Bm25Index.build(chunks);

        double[] scores = index.scores("大功率电器");

        assertEquals(3, index.size());
        assertTrue(scores[0] > scores[1], "含'大功率电器'的块分数应该更高");
        assertTrue(scores[0] > scores[2], "含'大功率电器'的块分数应该更高");
        // 另外两块完全没命中，分数必须是 0
        assertEquals(0.0, scores[1], 1e-9);
        assertEquals(0.0, scores[2], 1e-9);
    }

    @Test
    void 全库都没有的词应该不产生分数() {
        List<DocumentDao.ChunkWithEmbedding> chunks = List.of(
                new DocumentDao.ChunkWithEmbedding(1L, "A", 0, "学生证补办流程", "")
        );
        Bm25Index index = Bm25Index.build(chunks);

        double[] scores = index.scores("量子计算机");

        assertEquals(0.0, scores[0], 1e-9);
        assertEquals(0, index.documentFrequency("量子计算机"));
    }

    @Test
    void 覆盖率应该反映查询词被命中多少() {
        List<DocumentDao.ChunkWithEmbedding> chunks = List.of(
                new DocumentDao.ChunkWithEmbedding(1L, "A", 0, "学生证丢失补办", "")
        );
        Bm25Index index = Bm25Index.build(chunks);

        // "学生证补办" 的词全部能被块命中 → 覆盖率必须是 1
        assertEquals(1.0, index.idfCoverage("学生证补办", 0), 1e-9);
        // 块里没有"图书馆" → 覆盖率必须明显小于 1
        assertTrue(index.idfCoverage("图书馆", 0) < 1.0);
    }
}
