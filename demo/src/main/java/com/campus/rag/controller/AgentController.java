package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AgentLoop;
import com.campus.rag.service.ContextAssembler;
import com.campus.rag.config.AgentProperties;
import com.campus.rag.service.SessionDao;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 入口（D2 最小版：同步返回）。
 *
 * <p>为什么要单独一个入口而不塞进 /api/qa：两条链路的"可信度契约"不同——
 * 问答链路保证"每条结论都有引用"，Agent 链路保证"该查的时候查了、查到的能引用"。
 * 混在一起会让 120 题评测的口径变模糊，而那是这个项目最值钱的东西。
 *
 * <p>注意：这里刻意只做同步。SSE 版本要等"每步进度"这一层设计定下来再加，
 * 否则前端只能看到"很久没反应，然后一次性给出答案"。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentLoop agentLoop;
    private final AgentProperties props;
    private final SessionDao sessionDao;
    private final ContextAssembler contextAssembler;

    public AgentController(AgentLoop agentLoop, AgentProperties props, SessionDao sessionDao,
                           ContextAssembler contextAssembler) {
        this.agentLoop = agentLoop;
        this.props = props;
        this.sessionDao = sessionDao;
        this.contextAssembler = contextAssembler;
    }

    public record ChatRequest(String question, Integer topK, Long sessionId) {
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
                                    HttpServletRequest request) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (!props.isEnabled()) {
            // 开关关着时明确告知，而不是偷偷走问答链路——那会让排查变得极难
            Map<String, Object> off = new LinkedHashMap<>();
            off.put("status", "DISABLED");
            off.put("message", "Agent 未启用（campus.agent.enabled=false）");
            return off;
        }
        String question = req.question().trim();
        if (question.length() > 500) {
            question = question.substring(0, 500);
        }
        int topK = req.topK() == null ? props.getDefaultTopK() : Math.min(Math.max(1, req.topK()), 10);
        Long sessionId = req.sessionId() != null && sessionDao.findById(req.sessionId()) != null
                ? req.sessionId() : null;

        // 没有会话就建一个：多轮需要历史，历史需要 sessionId 落库
        if (sessionId == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "…" : question;
            sessionId = sessionDao.create(title, userId);
        }
        List<ContextAssembler.Turn> history = contextAssembler.load(sessionDao, sessionId);

        log.info("Agent 请求：user={} session={} history={} q={}",
                userId, sessionId, history.size(), question);
        AgentLoop.AgentResult r = agentLoop.run(question, topK, sessionId, userId, history);

        // 落库：下一轮的历史就从这里来。失败不阻断回答（问答是主线，记账是副线）
        try {
            sessionDao.insertMessage(sessionId, "user", question, false, null, null, null, null, null, null, null);
            sessionDao.insertMessage(sessionId, "assistant", r.answer(),
                    false, null, null, null, null, null, null, null);
        } catch (RuntimeException e) {
            log.warn("Agent 会话落库失败（不影响回答）：{}", e.toString());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", r.runId());
        body.put("sessionId", sessionId);
        body.put("status", r.status());
        body.put("answer", r.answer());
        body.put("steps", r.steps());
        body.put("allowedChunks", r.allowedChunks());
        body.put("totalMs", r.totalMs());
        body.put("promptTokens", r.promptTokens());
        body.put("completionTokens", r.completionTokens());
        body.put("totalTokens", r.totalTokens());
        body.put("costYuan", r.costYuan());
        if (r.error() != null) {
            body.put("error", r.error());
        }
        return body;
    }
}
