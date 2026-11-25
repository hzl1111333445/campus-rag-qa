package com.campus.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装器的单测：不连数据库，只验证"取哪几条、怎么截断、顺序对不对"。
 *
 * <p>为什么这几条值得测：历史带错了不会报错，只会让答案悄悄变差——
 * 这种"静默降级"最该有测试守着。
 */
class ContextAssemblerTest {

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static ContextAssembler assembler(int turns, int perMsg, int total) {
        return new ContextAssembler(true, turns, perMsg, total);
    }

    @Test
    @DisplayName("只带最近 N 轮，且恢复成时间正序")
    void keepsOnlyRecentTurns() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(msg("user", "问题" + i));
            rows.add(msg("assistant", "回答" + i));
        }

        List<ContextAssembler.Turn> history = assembler(2, 120, 600).assemble(rows);

        assertEquals(4, history.size(), "2 轮 = 4 条");
        assertEquals("问题4", history.get(0).content());
        assertEquals("回答4", history.get(1).content());
        assertEquals("问题5", history.get(2).content());
        assertEquals("回答5", history.get(3).content());
    }

    @Test
    @DisplayName("单条超长要截断：头 60% + 尾 40%，中间省略号（2026-11-25 修正）")
    void truncatesLongMessage() {
        // 头尾用不同字符，才能验证"两边都留了"
        String longText = "头".repeat(200) + "尾".repeat(100);
        List<Map<String, Object>> rows = List.of(msg("assistant", longText));

        List<ContextAssembler.Turn> history = assembler(3, 120, 600).assemble(rows);

        String got = history.get(0).content();
        assertEquals(121, got.length(), "120 字 + 省略号");
        assertTrue(got.contains("…"), "中间要有省略号");
        assertTrue(got.startsWith("头"), "开头要保留");
        assertTrue(got.endsWith("尾"), "结尾要保留（这是 2026-11-25 修掉的真实降质点）");
    }

    @Test
    @DisplayName("总字数超上限时，整条丢弃而不是截半条")
    void respectsTotalBudget() {
        List<Map<String, Object>> rows = List.of(
                msg("user", "甲".repeat(100)),
                msg("assistant", "乙".repeat(100)),
                msg("user", "丙".repeat(100)));

        // 上限 250：从最近的"丙"开始 100，加上"乙"100 = 200，再加"甲"就 300 超了 → 丢"甲"
        List<ContextAssembler.Turn> history = assembler(9, 120, 250).assemble(rows);

        assertEquals(2, history.size());
        assertEquals("乙".repeat(100), history.get(0).content());
        assertEquals("丙".repeat(100), history.get(1).content());
    }

    @Test
    @DisplayName("多行内容压成一行，避免把提示词结构搅乱")
    void flattensNewlines() {
        List<Map<String, Object>> rows = List.of(msg("assistant", "第一行\n第二行\t\t第三行"));

        List<ContextAssembler.Turn> history = assembler(3, 120, 600).assemble(rows);

        assertEquals("第一行 第二行 第三行", history.get(0).content());
    }

    @Test
    @DisplayName("turns=0 或空表时返回空，不影响单轮行为")
    void handlesEmpty() {
        assertTrue(assembler(0, 120, 600).assemble(List.of(msg("user", "问题"))).isEmpty());
        assertTrue(assembler(3, 120, 600).assemble(List.of()).isEmpty());
    }

    @Test
    @DisplayName("关掉开关就退回单轮（enabled=false）")
    void disabledReturnsEmpty() {
        ContextAssembler off = new ContextAssembler(false, 3, 120, 600);
        SessionDao fake = null;   // 关了开关不该碰数据库
        // load 在 enabled=false 时直接返回空，不会解引用 sessionDao
        assertTrue(off.load(fake, 1L).isEmpty());
    }

    @Test
    @DisplayName("读配置的构造器不把非法值传下去")
    void clampsConfig() {
        ContextAssembler weird = new ContextAssembler(true, -3, 1, 1);
        assertTrue(weird.assemble(List.of(msg("user", "问题"))).isEmpty(), "turns<=0 时为空");
        // 其余参数被夹到最小值；这只验证不抛异常
        ReflectionTestUtils.getField(weird, "maxCharsPerMsg");
    }

    @Test
    @DisplayName("历史里的引用编号要被抹掉，否则模型会抄到本轮答案里")
    void stripsCitationMarkers() {
        List<Map<String, Object>> rows = List.of(msg("assistant", "旷课51学时给予留校察看一年[1]，另见[3]。"));
        List<ContextAssembler.Turn> history = assembler(3, 120, 600).assemble(rows);
        assertEquals("旷课51学时给予留校察看一年，另见。", history.get(0).content());
    }
}
