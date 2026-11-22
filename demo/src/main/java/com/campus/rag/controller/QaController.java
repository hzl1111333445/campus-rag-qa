package com.campus.rag.controller;

import com.campus.rag.service.QaService;
import com.campus.rag.service.SessionDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 问答接口：检索 + 生成 + 引用。
 *
 * <p>两个入口：
 * <ul>
 *   <li>POST /api/qa        —— 真回答（会调大模型，花钱、慢）</li>
 *   <li>GET  /api/qa/preview —— 只看检索到了什么、提示词长什么样（不花钱，调试用）</li>
 * </ul>
 *
 * <p>2026-11-22 起，POST /api/qa 会顺手把这一问一答存进会话表：
 * <ul>
 *   <li>请求体可带 sessionId；不带就自动新建一个会话（标题=问题的前 20 字）</li>
 *   <li>本次会话 id 从响应头 X-Session-Id 回传 —— 之所以不放返回体，
 *       是为了不改动已有的返回结构（评测脚本和其他调用方都不用改）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    private final QaService qaService;
    private final SessionDao sessionDao;
    private final ObjectMapper objectMapper;

    public QaController(QaService qaService, SessionDao sessionDao, ObjectMapper objectMapper) {
        this.qaService = qaService;
        this.sessionDao = sessionDao;
        this.objectMapper = objectMapper;
    }

    /** 请求体：{"question":"违纪处分有哪几种","topK":3,"sessionId":12} */
    public record AskRequest(String question, Integer topK, Long sessionId) {
    }

    @PostMapping
    public ResponseEntity<QaService.QaAnswer> ask(@RequestBody AskRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String question = req.question().trim();
        int topK = req.topK() == null ? 3 : Math.max(1, req.topK());

        // 先定会话：没给或给的 id 不存在，就新建一个，标题取问题前 20 字
        long sessionId = resolveSession(req.sessionId(), question);

        QaService.QaAnswer answer = qaService.ask(question, topK);

        // 存消息。存失败不影响本次回答——问答是主线，记账是副线，副线出错只记日志。
        try {
            sessionDao.insertMessage(sessionId, "user", question,
                    false, null, null, null, null, null, null, null);
            sessionDao.insertMessage(sessionId, "assistant", answer.answer(),
                    answer.gated(),
                    answer.citationOk(),
                    answer.grounded(),
                    answer.factCount(),
                    answer.topScore(),
                    answer.elapsedMs(),
                    toJson(answer.references()),
                    joinFacts(answer.missingFacts()));
        } catch (Exception e) {
            log.warn("保存会话消息失败 sessionId={}: {}", sessionId, e.toString());
        }

        return ResponseEntity.ok()
                .header("X-Session-Id", String.valueOf(sessionId))
                .body(answer);
    }

    /** 调试：只看检索结果和拼好的提示词，不调模型 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("q") String q,
                                       @RequestParam(defaultValue = "3") int topK) {
        return qaService.preview(q, Math.max(1, topK));
    }

    private long resolveSession(Long sessionId, String question) {
        if (sessionId != null && sessionDao.findById(sessionId) != null) {
            return sessionId;
        }
        String title = question.length() > 20 ? question.substring(0, 20) + "…" : question;
        return sessionDao.create(title);
    }

    private String toJson(List<QaService.Reference> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            log.warn("引用快照序列化失败: {}", e.toString());
            return null;
        }
    }

    private static String joinFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        String s = String.join("、", facts);
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
