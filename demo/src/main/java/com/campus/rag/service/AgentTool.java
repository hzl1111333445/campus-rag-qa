package com.campus.rag.service;

import java.util.List;
import java.util.Map;

/**
 * Agent 可调用的工具。
 *
 * <p>设计约束：
 * <ul>
 *   <li>工具不抛异常，失败也返回结构化结果 —— 抛异常会崩掉循环，模型没机会纠错。</li>
 *   <li>返回值带 chunkIds：这是"引用必须真实"的地基，服务端只认本轮见过的块。</li>
 *   <li>description 写给模型看，决定它会不会选这个工具。</li>
 * </ul>
 */
public interface AgentTool {

    String name();

    String description();

    Map<String, Object> schema();

    ToolResult run(Map<String, Object> args);

    record ToolResult(boolean ok, String observation, List<Long> chunkIds) {

        public static ToolResult fail(String reason) {
            return new ToolResult(false, "工具执行失败：" + reason, List.of());
        }
    }
}