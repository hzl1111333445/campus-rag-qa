package com.campus.rag.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分词器：把一句话切成"词"，给 BM25 用。
 *
 * <p><b>为什么要分词？</b>
 * BM25 是"按词匹配"的算法，它需要知道一句话里出现了哪些词。
 * 英文天生有空格分词，中文没有——"违纪处分"是 1 个词还是 4 个字？
 *
 * <p><b>我们用的办法：单字 + 相邻双字（bigram）</b>
 * 比如「学生证」→ 学、生、证、学生、生证
 * <ul>
 *   <li>单字：保证召回（只要出现"学"就算沾边）</li>
 *   <li>双字：保证精度（"学生"比单独的"学"更有意义）</li>
 * </ul>
 *
 * <p><b>为什么不用 jieba / IK 分词器？</b>
 * 它们要加载几十 MB 词典，配置也麻烦；而 bigram 在"短文本检索"这个场景上
 * 效果已经接近真分词（这也是业界常用的取巧办法）。
 * 想升级的话，换成 IK 只改这一个类，BM25 那边不用动——这叫"接口隔离"。
 */
public final class Tokenizer {

    private Tokenizer() {
        // 全是静态方法，不需要创建对象
    }

    /**
     * 把文本切成词列表（会去重顺序保留，由调用方决定要不要去重）。
     *
     * @param text 任意文本，比如一个问题或者一个文本块
     */
    public static List<String> tokenize(String text) {
        String s = clean(text);
        List<String> tokens = new ArrayList<>(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            tokens.add(String.valueOf(s.charAt(i)));              // 单字
            if (i + 1 < s.length()) {
                tokens.add(s.substring(i, i + 2));                // 双字 bigram
            }
        }
        return tokens;
    }

    /**
     * 只保留中文汉字和英文字母、数字，其余（标点、空格、换行）全部丢掉。
     *
     * <p>为什么要丢标点？因为 BM25 算的是"词频"，标点不携带语义，
     * 保留它们只会让"。「" 这种符号也变成一个 token，白白干扰打分。
     */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean cjk = c >= '\u4e00' && c <= '\u9fff';   // 常用汉字区间
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (cjk || alnum) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
