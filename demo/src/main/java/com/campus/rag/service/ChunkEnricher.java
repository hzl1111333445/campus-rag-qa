package com.campus.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 块头增强器：给每个文本块拼上「文档标题 + 所在章节」。
 *
 * <p><b>为什么需要它？</b>
 * 切分只按字数切，切出来的块是"裸的"。比如《综合测评条例》的第 0 块，
 * 原文开头是页码 {@code — 3—}，然后直接"学业表现分=基本分—扣分…"，
 * 里面根本没有"综合测评"四个字 —— 于是用户搜"综合测评里学业成绩占多大比重"时，
 * 这个块既没有字面分（BM25 打不中），向量也不够像。
 *
 * <p><b>加了前缀会怎样？</b>
 * 块内容变成：
 * <pre>
 * 【02-广州软件学院学生综合测评条例（2026年修订） · 第三章 综合表现测评】
 * 学业表现分=基本分—扣分。该项得分乘以 70%后计入总分…
 * </pre>
 * 这样"综合测评"、"第三章"这些词就进了这个块，BM25 能打中，向量也更接近问题。
 *
 * <p><b>为什么在"入库时"加，而不是"检索时"临时加？</b>
 * 因为向量是在入库时算的。前缀必须参与向量计算，检索时再加就晚了
 * （除非每次检索都重算全部向量，那要 6 秒一次）。
 *
 * <p><b>代价</b>：每块多 20~40 字，会占用一点上下文长度；
 * 引用卡片显示的原文也会带这行前缀（其实是好事，用户能知道这句在哪一章）。
 */
@Service
public class ChunkEnricher {

    /**
     * 章节标题：第X章 + 后面的短标题。
     * 只认"章"，不认"条"——条太多（54 条），每条都当前缀反而把正文稀释了。
     */
    private static final Pattern CHAPTER = Pattern.compile("第[一二三四五六七八九十百零〇\\d]{1,4}章[ \\u3000]*[^\\n。；:：]{0,20}");

    /** 前缀最长长度，防止异常文本把整块撑爆。 */
    private static final int MAX_PREFIX = 80;

    /**
     * 给一批块加前缀。
     *
     * <p>章节是"继承"的：第 5 块自己没写"第三章"，就沿用前面最近的第三章。
     * 如果一块都还没见过章标题，就只拼文档标题。
     *
     * @param documentTitle 文档标题（来自数据库 title 字段，不是文件名）
     * @param chunks        切分器切出来的裸块
     * @return 加了前缀的新列表（长度、顺序不变，一一对应）
     */
    public List<String> enrich(String documentTitle, List<String> chunks) {
        String title = documentTitle == null ? "" : documentTitle.trim();
        List<String> result = new ArrayList<>(chunks.size());
        String currentChapter = null;

        for (String chunk : chunks) {
            String chapterInChunk = lastChapterOf(chunk);
            if (chapterInChunk != null) {
                currentChapter = chapterInChunk;
            }
            result.add(prefix(title, currentChapter) + chunk);
        }
        return result;
    }

    /** 取块里出现的最后一个章节标题（块内通常只有一个，取最后一个更稳）。 */
    static String lastChapterOf(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = CHAPTER.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group().trim();
        }
        return last;
    }

    /** 拼前缀：{@code 【标题 · 第X章】\n}；没有章就只要标题。 */
    static String prefix(String title, String chapter) {
        StringBuilder sb = new StringBuilder(MAX_PREFIX + 2);
        sb.append('【').append(title);
        if (chapter != null && !chapter.isEmpty()) {
            sb.append(" · ").append(chapter);
        }
        sb.append('】');
        String p = sb.toString();
        if (p.length() > MAX_PREFIX) {
            p = p.substring(0, MAX_PREFIX - 1) + "】";
        }
        return p + "\n";
    }
}
