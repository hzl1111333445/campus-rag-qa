package com.campus.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.campus.rag.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ReAct 循环（Thought → Action → Observation → … → Final）。
 *
 * <p>七条铁律（写死在代码里，不靠提示词）：
 * <ol>
 *   <li>第 1 步强制 search_rules（把"要不要查"从模型手里拿走，保可靠性）</li>
 *   <li>最多 campus.agent.max-steps 步</li>
 *   <li>同工具同参数重复超过 max-same-call 次即中止</li>
 *   <li>每步落 agent_step，Observation 截断</li>
 *   <li>final_answer 的引用编号必须落在本轮见过的块里，越界即降级</li>
 *   <li>无引用 / 校验失败 / 超步数 / 解析失败 → 降级回固定管线 QaService.ask</li>
 *   <li>模型输出必须严格 JSON，解析失败重试一次再降级</li>
 * </ol>
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    private final AgentProperties props;
    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final AgentDao dao;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final QaService qaService;

    public AgentLoop(AgentProperties props, ChatClient chatClient, ObjectMapper mapper,
                     AgentDao dao, List<AgentTool> toolList, QaService qaService) {
        this.props = props;
        this.chatClient = chatClient;
        this.mapper = mapper;
        this.dao = dao;
        this.qaService = qaService;
        for (AgentTool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    /** 执行结果（给 Controller 用）。 */
    public record AgentResult(long runId, String status, String answer,
                              List<Long> allowedChunks, int steps, long totalMs,
                              int tokenEst, String error) {
    }

    public AgentResult run(String question, int topK, Long sessionId, Long userId) {
        long begin = System.currentTimeMillis();
        long runId = dao.createRun(sessionId, userId, question);
        List<Long> evidence = new ArrayList<>();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt()));
        messages.add(msg("user", "<question>" + question + "</question>"));

        int steps = 0;
        String prevCall = null;
        int sameCount = 0;
        int parseRetried = 0;

        try {
            while (steps < props.getMaxSteps()) {
                steps++;
                String raw = chatClient.chat(messages);
                JsonNode node = parseJson(raw);
                if (node == null) {
                    if (parseRetried++ == 0) {
                        messages.add(msg("assistant", raw));
                        messages.add(msg("user", "你的输出不是合法 JSON。请只输出 JSON，不要解释、不要 markdown 代码块。"));
                        continue;
                    }
                    return degrade(runId, question, topK, steps, begin, "模型输出不是合法 JSON");
                }

                String thought = node.path("thought").asText("");
                String action = node.path("action").asText("").trim();
                Map<String, Object> args = argsOf(node.path("action_input"));

                if ("final".equals(action)) {
                    String answer = node.path("final_answer").asText("");
                    if (answer.isBlank()) {
                        return degrade(runId, question, topK, steps, begin, "final 但 final_answer 为空");
                    }
                    List<Integer> bad = badCitations(answer, evidence.size());
                    if (!bad.isEmpty()) {
                        return degrade(runId, question, topK, steps, begin,
                                "引用编号越界 " + bad + "（本轮只有 " + evidence.size() + " 条证据）");
                    }
                    long cost = System.currentTimeMillis() - begin;
                    dao.finishRun(runId, "DONE", steps, cost, estimateTokens(messages), answer,
                            joinIds(evidence), null);
                    return new AgentResult(runId, "DONE", answer, evidence, steps, cost,
                            estimateTokens(messages), null);
                }

                // 铁律 1：第一步必须检索
                if (steps == 1 && !"search_rules".equals(action)) {
                    log.info("Agent 第 1 步想调用 {}，强制改为 search_rules", action);
                    action = "search_rules";
                    args = Map.of("query", question, "topK", props.getDefaultTopK());
                }

                // 铁律 3：同工具同参防死循环
                String callKey = action + "|" + json(args);
                if (callKey.equals(prevCall)) {
                    sameCount++;
                } else {
                    sameCount = 1;
                    prevCall = callKey;
                }
                if (sameCount > props.getMaxSameCall()) {
                    return degrade(runId, question, topK, steps, begin, "同一调用重复超过 " + props.getMaxSameCall() + " 次");
                }

                AgentTool tool = tools.get(action);
                if (tool == null) {
                    // 让模型重选，不降级
                    messages.add(msg("assistant", raw));
                    messages.add(msg("user", "<observation>未知工具：" + action
                            + "。可用工具：" + String.join(",", tools.keySet()) + "</observation>"));
                    continue;
                }

                long t0 = System.currentTimeMillis();
                AgentTool.ToolResult result = tool.run(args);
                long cost = System.currentTimeMillis() - t0;
                String observation = truncate(result.observation());
                dao.appendStep(runId, steps, thought, action, json(args),
                        observation, result.ok(), cost);
                if (result.ok()) {
                    for (Long id : result.chunkIds()) {
                        if (!evidence.contains(id)) {
                            evidence.add(id);
                        }
                    }
                }
                messages.add(msg("assistant", raw));
                messages.add(msg("user", "<observation tool=\"" + action + "\">" + observation + "</observation>"));
            }
            return degrade(runId, question, topK, steps, begin, "超过最大步数 " + props.getMaxSteps());
        } catch (RuntimeException e) {
            log.warn("Agent 循环异常：{}", e.toString());
            return degrade(runId, question, topK, steps, begin, e.toString());
        }
    }

    /** 降级：走固定管线，保证"至少不至于没有答案"。 */
    private AgentResult degrade(long runId, String question, int topK, int steps, long begin, String reason) {
        long cost = System.currentTimeMillis() - begin;
        try {
            QaService.QaAnswer answer = qaService.ask(question, topK);
            dao.finishRun(runId, "DEGRADED", steps, cost, 0, answer.answer(), null, reason);
            log.info("Agent 降级回固定管线：{}", reason);
            return new AgentResult(runId, "DEGRADED", answer.answer(), List.of(), steps, cost, 0, reason);
        } catch (RuntimeException e) {
            dao.finishRun(runId, "FAILED", steps, cost, 0, null, null, reason + " / 降级也失败：" + e);
            return new AgentResult(runId, "FAILED", null, List.of(), steps, cost, 0, reason);
        }
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个校园制度问答 Agent。你只能通过调用工具获取事实，不能凭记忆编造学校规定。\n");
        sb.append("每一步只输出一个 JSON 对象，不要输出别的文字，不要用 markdown 代码块。\n\n");
        sb.append("输出格式（二选一）：\n");
        sb.append("1) 调工具：{\"thought\":\"为什么调这个工具\",\"action\":\"工具名\",\"action_input\":{...}}\n");
        sb.append("2) 给结论：{\"thought\":\"信息够了\",\"action\":\"final\",\"final_answer\":\"结论正文\"}\n\n");
        sb.append("规则：\n");
        sb.append("- 第 1 步必须调用 search_rules 检索证据。\n");
        sb.append("- 引用编号 [n] 只能使用 <observation> 里出现过的序号，越界会被系统判为失败。\n");
        sb.append("- 信息不足以下结论时，直接说清楚还缺什么，不要猜。\n");
        sb.append("- 最多 ").append(props.getMaxSteps()).append(" 步。\n\n");
        sb.append("可用工具：\n");
        for (AgentTool t : tools.values()) {
            sb.append("- ").append(t.name()).append("：").append(t.description()).append("\n");
            sb.append("  参数：").append(t.schema()).append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> argsOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (node.isObject()) {
            return mapper.convertValue(node, Map.class);
        }
        return Map.of();
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            // readTree 抛受检 JsonProcessingException，统一吞掉当"不是 JSON"
            return null;
        }
    }

    private List<Integer> badCitations(String answer, int size) {
        List<Integer> bad = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > size) {
                bad.add(n);
            }
        }
        return bad;
    }


    /** 序列化失败不抛（写日志用，失败给个空对象）。 */
    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        int max = props.getMaxObservationChars();
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    private static Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static int estimateTokens(List<Map<String, String>> messages) {
        int chars = 0;
        for (Map<String, String> m : messages) {
            chars += m.get("content") == null ? 0 : m.get("content").length();
        }
        return chars / 2;
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}