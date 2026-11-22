package com.campus.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnswerGuard 的测试：先看着它红，填完两个 TODO 后变绿。
 *
 * <p>运行方式：
 * <ul>
 *   <li>IDEA：点类名左边的绿三角，或 Ctrl+Shift+F10</li>
 *   <li>命令行：{@code mvn test -Dtest=AnswerGuardTest}</li>
 * </ul>
 */
class AnswerGuardTest {

    /** 真实的《图书借阅规则》原文片段 */
    private static final String BORROW_RULE = """
            三、教师每证可借图书20册，借期60天，到期之前可续借一次，续借期仍为60天。
            学生每证可借图书10册，借期30天，到期之前可续借一次，续借期仍为30天。
            逾期每册每日需交超期违约金0.10元，缴清超期违约金前暂停借书。
            """;

    /** 真实的《学生考勤管理制度》原文片段 */
    private static final String ATTENDANCE_RULE = """
            (2)旷课31-40学时，给予严重警告处分；
            (3)旷课41-50学时，给予记过处分；
            (4)旷课51学时及以上者，给予留校察看一年处分(毕业班的学生给予记过处分)；
            """;

    @Test
    @DisplayName("数字完全一致 → 判为可信")
    void allFactsPresent() {
        String answer = "图书逾期每册每日需交0.10元，缴清前暂停借书[1]。";
        AnswerGuard.Check c = AnswerGuard.check(answer, List.of(BORROW_RULE));
        assertTrue(c.grounded(), "0.10元 和 原文一致，应该判可信；缺的是 " + c.missing());
        assertEquals(0, c.missingCount());
    }

    @Test
    @DisplayName("数字被改错（0.20 vs 0.10）→ 必须抓出来")
    void wrongNumberCaught() {
        String answer = "图书逾期每册每日需交0.20元，缴清前暂停借书[1]。";
        AnswerGuard.Check c = AnswerGuard.check(answer, List.of(BORROW_RULE));
        assertFalse(c.grounded(), "0.20元 原文里没有，必须判不可信");
        assertTrue(c.missing().contains("0.20元"), "missing 里应该有 0.20元，实际：" + c.missing());
    }

    @Test
    @DisplayName("0.10 与 0.1 是同一个数 → 不应该误报")
    void trailingZeroEquivalence() {
        String answer = "逾期每册每日交0.1元违约金[1]。";
        AnswerGuard.Check c = AnswerGuard.check(answer, List.of(BORROW_RULE));
        assertTrue(c.grounded(), "0.1元 与 0.10元 等价，不该误报；缺的是 " + c.missing());
    }

    @Test
    @DisplayName("全角数字 + 单位前有空格 → 不应该误报")
    void fullWidthAndSpaces() {
        String answer = "逾期每天要交 ０.１０ 元[1]。";
        AnswerGuard.Check c = AnswerGuard.check(answer, List.of(BORROW_RULE));
        assertTrue(c.grounded(), "全角 ０.１０ 元 应归一化成 0.10元；缺的是 " + c.missing());
    }

    @Test
    @DisplayName("处分名称：原文有的命中，原文没有的抓出来")
    void punishmentNames() {
        String okAnswer = "旷课50学时给予记过处分[1]。";
        assertTrue(AnswerGuard.check(okAnswer, List.of(ATTENDANCE_RULE)).grounded(),
                "记过 原文有，应可信");

        String badAnswer = "旷课50学时会被开除学籍[1]。";
        AnswerGuard.Check c = AnswerGuard.check(badAnswer, List.of(ATTENDANCE_RULE));
        assertFalse(c.grounded(), "「开除学籍」在考勤制度这段原文里没有，必须抓出来");
        assertTrue(c.missing().contains("开除学籍"), "实际 missing：" + c.missing());
    }

    @Test
    @DisplayName("被引用多块时，事实出现在其中一块就算命中")
    void factInAnyCitedChunk() {
        String answer = "逾期每册每日0.10元[1]，旷课51学时留校察看[2]。";
        AnswerGuard.Check c = AnswerGuard.check(answer, List.of(BORROW_RULE, ATTENDANCE_RULE));
        assertTrue(c.grounded(), "两块分别含两条事实，应判可信；缺的是 " + c.missing());
    }
}
