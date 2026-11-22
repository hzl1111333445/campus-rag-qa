package com.campus.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * groundedness（接地性）校验：答案里的"关键事实"，必须在它引用的原文里真的出现。
 *
 * <p>和现有的引用编号校验（{@code QaService} 里那段）区别：
 * <ul>
 *   <li>编号校验：回答写 [3]，但只检索到 2 块 → 抓"编号编造"</li>
 *   <li>本类校验：回答写 [1]，内容也来自块 1，但把 0.10 元说成 0.20 元 → 抓"内容与原文不符"</li>
 * </ul>
 *
 * <p><b>为什么只抽"数字 + 单位、时间点、处分类别"</b>：其它词模型一定会改述
 * （"不得" → "禁止"、"23:30" → "晚上十一点半"），逐字比对会误报一片；
 * 而数字/金额/学时/瓦数是"改了就错"的硬事实，最适合自动核对。
 *
 * <p>这个类留给学生的两个 TODO 分别在 {@link #normalizeDigits(String)} 和
 * {@link #check(String, List)} 里，填完 {@code AnswerGuardTest} 才会全绿。
 */
public final class AnswerGuard {

    private AnswerGuard() {
    }

    /** 一条"可核对的事实" */
    public record Fact(String text, String kind) {
    }

    /** 校验结果 */
    public record Check(int factCount, int missingCount, List<String> missing, boolean grounded) {
    }

    /** 数字 + 单位：0.10元、1500瓦、51学时、10册、30天、20% */
    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:学时|学分|元|天|个月|年|瓦|册|个工作日|工作日|%)");

    /** 时间点：23:30、6:00（也兼容全角冒号，normalizeDigits 里处理） */
    private static final Pattern TIME_POINT = Pattern.compile("\\d{1,2}:\\d{2}");

    /** 处分类别：这几个词不会变，变了就是错 */
    private static final List<String> PUNISHMENTS = List.of(
            "严重警告", "警告", "记过", "留校察看", "开除学籍");

    /**
     * 从答案里抽出所有可核对的事实（去重，保持出现顺序）。
     */
    public static List<Fact> extract(String answer) {
        List<Fact> facts = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return facts;
        }
        Set<String> seen = new LinkedHashSet<>();

        // 先把全角转半角，否则 ０.１０ 这种写法连正则都匹配不到（Java 的 \d 只认半角）
        // 注意：这里只转字符、不去空格也不去末尾 0，为的是让 missing 里显示的是"人看得懂的原样"
        String halfWidth = toHalfWidth(answer);

        Matcher m = NUMBER_UNIT.matcher(halfWidth);
        while (m.find()) {
            String t = m.group().replaceAll("\\s+", "");
            if (seen.add("N:" + t)) {
                facts.add(new Fact(t, "数字"));
            }
        }

        Matcher tm = TIME_POINT.matcher(answer);
        while (tm.find()) {
            String t = tm.group();
            if (seen.add("T:" + t)) {
                facts.add(new Fact(t, "时间"));
            }
        }

        for (String p : PUNISHMENTS) {
            if (answer.contains(p) && seen.add("P:" + p)) {
                facts.add(new Fact(p, "处分"));
            }
        }
        return facts;
    }

    /**
     * 数字归一化：让"看起来不一样、其实是同一个数"的写法能被认成同一个。
     *
     * <p>TODO 1（你填）—— 需要处理这几种情况：
     * <ol>
     *   <li>小数末尾的 0：{@code 0.10} 与 {@code 0.1} 应视为相同 → 去掉小数末尾多余的 0</li>
     *   <li>全角字符：{@code ０.１０}（全角数字与全角点）→ 转半角</li>
     *   <li>数字与单位之间的空格：{@code 1500 瓦} → {@code 1500瓦}</li>
     * </ol>
     * 提示：可以先 replace 全角字符，再用正则 {@code (\\.\\d*?)0+(?=\\D|$)} 去掉末尾 0，
     * 最后 {@code replaceAll("\\s+", "")}。注意别把整数里的 0 去掉（{@code 1000元} 不能变 {@code 1元}）。
     *
     * @param text 可能包含数字的字符串
     * @return 归一化后的字符串（用于 contains 比对，不用于显示）
     */
    /**
     * 全角转半角：把 ０-９、．、：、％ 和全角空格换成半角字符。
     *
     * <p>为什么单独抽一个方法：抽事实（extract）和比较（normalizeDigits）都要用它，
     * 但两个地方的用途不同——extract 只转字符、保留空格与末尾 0，为的是让 missing 里显示原样；
     * normalizeDigits 则要彻底归一化。
     */
    private static String toHalfWidth(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (ch >= '０' && ch <= '９') {
                sb.append((char) (ch - '０' + '0'));
            } else if (ch == '．') {
                sb.append('.');
            } else if (ch == '：') {
                sb.append(':');
            } else if (ch == '％') {
                sb.append('%');
            } else if (ch == '　') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 数字归一化：让"看起来不一样、其实是同一个数"的写法能被认成同一个。
     *
     * <p>处理四件事：
     * <ol>
     *   <li>全角字符：{@code ０.１０} → {@code 0.10}</li>
     *   <li>数字与单位之间的空格：{@code 1500 瓦} → {@code 1500瓦}</li>
     *   <li>小数末尾多余的 0：{@code 0.10} → {@code 0.1}（整数里的 0 不动）</li>
     *   <li>只剩一个孤零零的小数点：{@code 0.} → {@code 0}</li>
     * </ol>
     *
     * @param text 可能包含数字的字符串
     * @return 归一化后的字符串（只用于 contains 比对，不用于显示）
     */
    public static String normalizeDigits(String text) {
        // 第 1 步：全角转半角 + 去掉所有空白（"０.１０ 元" → "0.10元"）
        String s = toHalfWidth(text).replaceAll("\\s+", "");
        // 第 2 步：去掉小数末尾多余的 0（"0.10" → "0.1"），整数不动（"1000元" 保持）
        s = s.replaceAll("(\\d+\\.\\d*?)0+(?=\\D|$)", "$1");
        // 第 3 步：去掉小数点后什么都不剩的点（"0." → "0"）
        s = s.replaceAll("(\\d+)\\.(?=\\D|$)", "$1");
        return s;
    }

    /**
     * 核心校验：答案里的每条事实，都要在"被引用的那些块的原文"里出现过。
     *
     * @param answer       模型生成的回答
     * @param citedContents 这条回答引用的块的原文（QaService 里已经把 content 带出来了）
     * @return 校验结果；{@code grounded = missingCount == 0}
     */
    public static Check check(String answer, List<String> citedContents) {
        List<Fact> facts = extract(answer);
        if (facts.isEmpty() || citedContents == null || citedContents.isEmpty()) {
            // 没有可核对的事实（比如纯流程描述），或没有引用块 → 不算"不接地"，但也不给通过
            return new Check(facts.size(), 0, List.of(), false);
        }

        // 先把所有被引用块拼成一个大字符串，再做归一化；这样"跨块被引用的数字"也能命中
        StringBuilder sb = new StringBuilder();
        for (String c : citedContents) {
            sb.append(normalizeDigits(c)).append('\n');
        }
        String haystack = sb.toString();

        List<String> missing = new ArrayList<>();
        // 逐条事实去原文里找：归一化后 contains 不到，就记进 missing
        for (Fact f : facts) {
            String needle = normalizeDigits(f.text());
            if (needle.isEmpty() || !haystack.contains(needle)) {
                missing.add(f.text());
            }
        }

        return new Check(facts.size(), missing.size(), missing, missing.isEmpty());
    }
}
