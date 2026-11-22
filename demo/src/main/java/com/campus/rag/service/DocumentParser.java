package com.campus.rag.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析：把 PDF / Word / txt 的字节，变成一段纯文本。
 *
 * <p>它只干一件事——"把纸上的字抠出来"。抠出来之后怎么切、怎么存，是后面别人的事。
 * 这就是为什么要单独一个类：职责单一，好测、好换。
 */
@Service
public class DocumentParser {

    /** 这些格式它自己就是纯文本，不需要 Tika 猜来猜去。 */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

    /**
     * 字符数低于这个值，就认为"没抠出字"。
     * 典型场景：扫描件 PDF（整页是图片，没有文字层）。
     *
     * <p>为什么是 50：实测我们下载的 17 份学校文件里，能读出文字的最少 2036 字，
     * 读不出的全是 0 字，所以 20 和 200 都能区分；定 50 是为了留出"一页纸的短通知"
     * （几十个字）不被误杀的空间。
     */
    private static final int SCANNED_THRESHOLD = 50;

    /**
     * 解析入口。
     *
     * @param bytes    文件原始字节（不是路径，字节更灵活：上传的内存文件、磁盘文件都能用）
     * @param fileName 原始文件名，用来判断是不是 txt/md，以及报错时能说清是哪个文件
     * @return 纯文本，换行统一成 \n
     */
    public String parse(byte[] bytes, String fileName) {
        String ext = extensionOf(fileName);

        String text = PLAIN_TEXT_EXTENSIONS.contains(ext)
                ? parsePlainText(bytes)
                : parseWithTika(bytes);

        // 统一换行符：Windows 的 \r\n 和 Mac 的 \r 都变成 \n，后面切分才不会出现怪字符
        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();

        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                    "没从这个文件里提取到文字。常见原因：①PDF 是扫描件（只有图片没有文字层）"
                            + "②文件加密或损坏。文件名：" + fileName);
        }
        // 阈值实验已做：改成 200 时，2036 字的那份文件依然能过，不会误杀；
        // 但一份真正的短通知可能只有几十字，所以最终定 50。
        if (text.length() < SCANNED_THRESHOLD) {
            throw new IllegalArgumentException(
                    "只提取到 " + text.length() + " 个字符，这个文件很可能没有文字层（扫描件），"
                            + "需要先做 OCR（把图片识别成文字）。文件名：" + fileName);
        }

        return text;
    }

    /** txt / md：直接按 UTF-8 解码。不用 Tika 是因为中文短文常被猜成 GBK，解出来是乱码。 */
    private String parsePlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // Windows 记事本保存的 UTF-8 文件开头可能有个看不见的 BOM（\uFEFF），去掉
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * 其它格式交给 Tika。
     *
     * <p>三行核心：
     * <ol>
     *   <li>{@code AutoDetectParser} —— 看文件头字节判断真实类型，然后挑对应的解析器</li>
     *   <li>{@code BodyContentHandler(-1)} —— 装文字的口袋；-1 表示"不限长度"</li>
     *   <li>{@code parse(输入流, 口袋, 元数据, 上下文)} —— 真正干活</li>
     * </ol>
     */
    private String parseWithTika(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            // 注意这个 -1：默认构造的 BodyContentHandler 只保留 10 万字符，
            // 超出部分会被静默丢掉。规章文件一般不会超，但大文件会莫名其妙"少一段"。
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(in, handler, new Metadata(), new ParseContext());
            return handler.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件解析失败：" + e.getMessage(), e);
        }
    }

    /** 取小写扩展名；没有扩展名返回空串。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
