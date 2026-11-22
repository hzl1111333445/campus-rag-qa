package com.campus.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器的验收测试。
 *
 * <p>运行方式：在 IDEA 里点这个类左边的小绿三角，或者在项目目录执行 {@code mvn test}。
 *
 * <p><b>现在它应该是红的</b>（因为 TextSplitter 里有两个 TODO 还没填）。
 * 把 TODO 填对，它变绿，这一步就完成了。
 */
class TextSplitterTest {

    /** 一段真实风格的学生综合测评条例（约 700 字），用来看切出来的块长什么样。 */
    private static final String SAMPLE = """
            第一条 为全面贯彻党的教育方针，落实立德树人根本任务，引导学生德智体美劳全面发展，
            根据学校实际，制定本条例。
            第二条 学生综合素质测评坚持公开、公平、公正的原则，坚持定量与定性相结合，
            采取学生自评、班级测评、学院审核三级评定方式。
            第三条 综合素质测评成绩由思想品德、学业成绩、身心素质、实践能力四个部分组成，
            满分一百分。其中思想品德占百分之二十，学业成绩占百分之五十，
            身心素质占百分之十，实践能力占百分之二十。
            第四条 思想品德测评内容包括政治表现、道德修养、纪律观念、文明礼貌四个方面。
            受到警告处分的，扣十分；受到严重警告处分的，扣十五分；
            受到记过处分的，扣二十分；受到留校察看处分的，本学期测评成绩记为零分。
            第五条 学业成绩按学年平均学分绩点折算，具体折算办法由教务处另行规定。
            第六条 实践能力测评包括社会实践、学科竞赛、志愿服务、创新创业等内容。
            参加省级以上学科竞赛获奖的，省级一等奖加十分，国家级一等奖加二十分。
            第七条 综合素质测评每学年进行一次，于每年九月进行，十月公布结果。
            第八条 测评结果作为评定奖学金、评优评先、推荐免试研究生的重要依据。
            第九条 学生对测评结果有异议的，可以在结果公布之日起五个工作日内，
            向所在学院提出书面申诉，学院应当在收到申诉之日起七个工作日内作出答复。
            第十条 本条例由学生工作处负责解释，自公布之日起施行。
            """;

    @Test
    void 长文本应该切成多块() {
        List<String> chunks = TextSplitter.split(SAMPLE, 100, 20);

        System.out.println("原文长度 = " + SAMPLE.strip().length() + " 字");
        System.out.println("切出块数 = " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            System.out.printf("  第 %d 块：长度 %d，开头「%s」%n",
                    i + 1, c.length(), c.substring(0, Math.min(15, c.length())));
        }

        assertTrue(chunks.size() >= 6,
                "原文约 " + SAMPLE.strip().length() + " 字，size=100 时应该切出 6 块以上，"
                        + "实际只有 " + chunks.size() + " 块 —— 检查 TODO 1（end 的算法）");
    }

    @Test
    void 每块长度不能超过size太多() {
        List<String> chunks = TextSplitter.split(SAMPLE, 100, 20);
        int limit = 100 + 20;
        for (String c : chunks) {
            assertTrue(c.length() <= limit,
                    "有一块长度 " + c.length() + "，超过了 " + limit + " —— 检查 TODO 1 是否少写了 Math.max/Math.min");
        }
        System.out.println("所有块长度都在 " + limit + " 以内 ✅");
    }

    @Test
    void 文本比size短时只切一块() {
        String shortText = "第一条 本条例适用于全校在籍本科学生。";
        List<String> chunks = TextSplitter.split(shortText, 100, 20);
        assertEquals(1, chunks.size());
        assertEquals(shortText, chunks.get(0));
    }

    @Test
    void 参数不合法要报错() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TextSplitter.split("随便一段文字", 100, 100));
    }
}
