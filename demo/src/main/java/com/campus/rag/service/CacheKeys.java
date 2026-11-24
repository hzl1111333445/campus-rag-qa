package com.campus.rag.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 缓存键的拼装规则（集中一处，避免键格式散落在各个 Service 里）。
 *
 * <p><b>为什么必须集中</b>：缓存最典型的线上事故是"键漏了某个影响输出的因子"，
 * 于是读到别的场景的结果。键只要有一处拼错，前面所有正确性工作都白做。
 *
 * <p><b>键里必须包含哪些东西</b>（少一个就会脏读）：
 * <ul>
 *   <li>问题的<b>归一化文本</b>：首尾空白、连续空白不同但语义相同，应命中同一条</li>
 *   <li>{@code topK}：取 3 条与取 5 条，答案可能不同</li>
 *   <li><b>索引指纹</b>（{@code config_hash}）：语料、切分参数、向量模型一变，旧答案立即作废</li>
 *   <li><b>模型名</b>：换模型答案必然不同</li>
 *   <li>{@link #PROMPT_VERSION}：提示词规则一改，答案就该变 —— 手动升版本号是这里的开关</li>
 * </ul>
 *
 * <p><b>为什么用 SHA-256 而不是把问题直接拼进键</b>：Redis 的键有长度成本，
 * 中文问题拼进去键会很长；而且问题里可能含空格、冒号，拼起来难排查。哈希后统一 64 位十六进制。
 */
public final class CacheKeys {

    /** 问答结果缓存的键前缀。语料变更时按这个前缀整体清理。 */
    public static final String PREFIX_QA = "qa:v1:";
    /** embedding 缓存的键前缀。 */
    public static final String PREFIX_EMB = "emb:v1:";
    /** 限流计数的键前缀。 */
    public static final String PREFIX_RATE = "rl:v1:";

    /**
     * 提示词版本号。
     *
     * <p><b>改 PromptBuilder 的 SYSTEM_PROMPT 时必须把它加一版</b>（p-v1 → p-v2），
     * 否则旧缓存会在新规则下继续返回老答案，看起来像"改了没生效"。
     */
    public static final String PROMPT_VERSION = "p-v1";

    private CacheKeys() {
    }

    /** 问答结果缓存的键。 */
    public static String qa(String question, int topK, String indexFingerprint, String model) {
        String raw = normalize(question) + "|k=" + topK
                + "|idx=" + (indexFingerprint == null ? "" : indexFingerprint)
                + "|m=" + (model == null ? "" : model)
                + "|" + PROMPT_VERSION;
        return PREFIX_QA + sha256(raw);
    }

    /** embedding 缓存的键：同一个模型 + 同一段文字 → 同一个向量。 */
    public static String embedding(String model, String text) {
        return PREFIX_EMB + sha256((model == null ? "" : model) + "|" + text);
    }

    /** 限流键：按"身份 + 分钟窗口"计数，窗口一变就是新键，自然过期。 */
    public static String rateLimit(String subject, long epochMinute) {
        return PREFIX_RATE + subject + ":" + epochMinute;
    }

    /**
     * 问题归一化：去首尾空白、连续空白压成一个、全角空格也压掉。
     *
     * <p>刻意不做"去标点 / 转小写"以外的激进清洗：那会把不同问题合到一起，
     * 而缓存宁可少命中，也不能答错。
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** SHA-256 十六进制摘要。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // JDK 里 SHA-256 一定存在，到这里说明 JDK 环境坏了，直接暴露
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }
}
