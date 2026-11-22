package com.campus.rag.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分器：把一整段长文本，切成若干个"块"（chunk）。
 *
 * <p><b>为什么必须切？</b>因为大模型一次能读的字有限（几千字），而规章制度全文有几万字；
 * 检索时也只能把"最像问题的几百字"塞给它，不能塞全文。
 *
 * <p><b>怎么切？</b>用一把宽度为 {@code size} 的尺子，从文本开头往右滑；
 * 每次停下来切一块，下一块往回退 {@code overlap} 个字（让相邻两块有重叠，避免一句话被切断后两边都不完整）。
 *
 * <pre>
 * 文本： 0-----------------------------------------------n
 * 第1块： [0, size)
 * 第2块：      [size-overlap, 2size-overlap)
 * 第3块：                   [2size-2overlap, ...)
 * </pre>
 *
 * <p><b>为什么要有 overlap？</b>假设"转专业需要满足以下条件"这句话正好被切在两块之间，
 * 第一块末尾有"转专业需要满足"，第二块开头有"以下条件"——单独看两块都不完整，检索到也答不出来。
 * 让它们重叠一段，这句话至少在某一块里是完整的。
 */
public class TextSplitter {

    /** 句子结束的标志。切分时尽量往后挪到这些字符后面，别把句子拦腰砍断。 */
    private static final String SENTENCE_END = "。！？；\n";

    private TextSplitter() {
    }

    /**
     * 把文本切成若干块。
     *
     * @param text    原文（调用前请确保没有大量空行）
     * @param size    每块的目标字符数
     * @param overlap 相邻两块重叠的字符数，必须小于 size
     */
    public static List<String> split(String text, int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap 必须 >= 0 且 < size");
        }

        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // 先把换行/连续空格压平：规章文件里常有 PDF 转来的硬换行和多余空格，
        // 不处理的话切出来的块里全是空行，白占长度。
        String t = normalize(text);
        int n = t.length();

        int start = 0;
        while (start < n) {

            // ===== 窗口右边界 =====
            // 期望是 start + size，但不能超出文本末尾。
            //   start + size —— 从当前起点往后数 size 个字
            //   Math.min(..., n) —— 别超过文本总长，否则 substring 会越界
            int end = Math.min(start + size, n);

            // ===== 句末回退 =====
            // 如果 end 前面不远就有句号/问号/分号，就把 end 挪到它后面，保证块尾是完整句子。
            // 只回退 size/2 以内，回退太多块就太短了。
            int lowerBound = Math.max(start + 1, end - size / 2);
            int minChunk = (int) (size * 0.65);          // ← 新增：块不能比目标的 65% 还短
            for (int i = end - 1; i >= lowerBound; i--) {
                if (SENTENCE_END.indexOf(t.charAt(i)) >= 0) {
                    if (i + 1 - start >= minChunk) {     // ← 新增：退回太多就不回退了，宁可硬切
                        end = i + 1;
                    }
                    break;
                }
            }

            String piece = t.substring(start, end).strip();
            if (!piece.isEmpty()) {
                if (piece.length() < 10 && !chunks.isEmpty()) {
                    int last = chunks.size() - 1;
                    chunks.set(last, chunks.get(last) + piece);   // ← 太短的尾巴，接回上一块
                } else {
                    chunks.add(piece);
                }
            }
            if (end >= n) {
                break;
            }

            // ===== 下一块的起点 =====
            // 前进，但往回退 overlap 个字，形成重叠。
            //   如果 overlap 很大（比如接近 size），end - overlap 可能小于等于 start，
            //   那样就会原地打转、死循环。下面那行 Math.max 就是兜底保护。
            int nextStart = end - overlap;

            start = Math.max(nextStart, start + 1);
        }
        return chunks;
    }

    /** 把连续的空白压成一个空格，去掉开头结尾空白。 */
    static String normalize(String text) {
        return text.replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\s*\\n+\\s*", "")   // ← PDF 的硬换行：中文换行处不该留空，直接删掉
                .strip();
    }
}
