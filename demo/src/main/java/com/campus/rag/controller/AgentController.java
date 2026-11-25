package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AgentLoop;
import com.campus.rag.service.AnswerGuard;
import com.campus.rag.service.DocumentDao;
import com.campus.rag.service.QaService;
import com.campus.rag.service.ContextAssembler;
import com.campus.rag.config.AgentProperties;
import com.campus.rag.service.SessionDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
    private final DocumentDao documentDao;

    public AgentController(AgentLoop agentLoop, AgentProperties props, SessionDao sessionDao,
                           ContextAssembler contextAssembler, DocumentDao documentDao) {
        this.agentLoop = agentLoop;
        this.props = props;
        this.sessionDao = sessionDao;
        this.contextAssembler = contextAssembler;
        this.documentDao = documentDao;
    }

    public record ChatRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 200, message = "最多 200 字")
            String question,
            @Min(value = 1, message = "至少 1 条")
            @Max(value = 10, message = "最多 10 条")
            Integer topK,
            Long sessionId) {
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest req,
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

        // 引用编号是否越界：Agent 模式下前端据此显示"引用校验通过/越界 N 处"
        int badCount = com.campus.rag.service.AgentLoop.badCitations(r.answer(), r.allowedChunks().size()).size();
        body.put("badCitationCount", badCount);

        // 出处回显：把 allowedChunks（id 列表）换成"文档名 + 块号 + 正文"，
        // 让 Agent 模式的回答和问答链路一样能点开看原文。
        List<QaService.Reference> references = buildReferences(r.allowedChunks());
        body.put("references", references);

        // 接地校验：沿用问答链路同一套 AnswerGuard，只核"硬事实"（数字/期限/处分名）。
        // 有引用时只核被引块；一条引用都没有时退回核全部证据块——否则 grounded 恒为 false，
        // 会把"诚实拒答"也标成红色失败。
        List<String> haystack = new ArrayList<>();
        List<Integer> cited = com.campus.rag.service.AgentLoop.citedIndexes(r.answer());
        for (QaService.Reference ref : references) {
            if (cited.isEmpty() || cited.contains(ref.index())) {
                haystack.add(ref.content());
            }
        }
        AnswerGuard.Check guard = AnswerGuard.check(r.answer(), haystack);
        body.put("citationOk", badCount == 0);
        body.put("grounded", guard.grounded());
        body.put("factCount", guard.factCount());
        body.put("missingFacts", guard.missing());
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

    /** 最多回显 8 条出处，顺序与检索结果一致（引用编号 [n] 就是这里的第 n 条）。 */
    private List<QaService.Reference> buildReferences(List<Long> allowedChunks) {
        if (allowedChunks == null || allowedChunks.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : allowedChunks) {
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
            if (ids.size() >= 8) {
                break;
            }
        }
        try {
            List<DocumentDao.ChunkDetail> details = documentDao.findChunksByIds(ids);
            List<QaService.Reference> refs = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i);
                DocumentDao.ChunkDetail d = details.stream().filter(x -> x.id() == id).findFirst().orElse(null);
                if (d == null) {
                    continue;
                }
                refs.add(new QaService.Reference(i + 1, id, d.documentTitle(), d.chunkIndex(), 0d, d.content()));
            }
            return refs;
        } catch (RuntimeException e) {
            log.warn("出处回显失败（不影响回答）：{}", e.toString());
            return List.of();
        }
    }
}
