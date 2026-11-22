package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.QaService;
import com.campus.rag.service.ContextAssembler;
import com.campus.rag.service.SessionAccess;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p>2026-11-23 起：带令牌提问会记到该用户名下，不带令牌则记为<b>匿名会话</b>
 * （$user_id$ 为 NULL，只有管理员能看见）——评测脚本 tools/eval-answer.py 因此不用改。
 * 若请求里带了别人的 sessionId，直接按"会话不存在"拒绝，避免往别人的会话里追加消息。
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
    private final ContextAssembler contextAssembler;
    private final ObjectMapper objectMapper;

    public QaController(QaService qaService, SessionDao sessionDao,
                        ContextAssembler contextAssembler, ObjectMapper objectMapper) {
        this.qaService = qaService;
        this.sessionDao = sessionDao;
        this.contextAssembler = contextAssembler;
        this.objectMapper = objectMapper;
    }

    /** 请求体：{"question":"违纪处分有哪几种","topK":3,"sessionId":12} */
    public record AskRequest(String question, Integer topK, Long sessionId) {
    }

    @PostMapping
    public ResponseEntity<QaService.QaAnswer> ask(
            @RequestBody AskRequest req,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
            @RequestAttribute(value = AuthInterceptor.ATTR_ROLE, required = false) String role,
            @RequestHeader(value = "X-No-Persist", required = false) String noPersist) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String question = req.question().trim();
        int topK = req.topK() == null ? 3 : Math.max(1, req.topK());

        // 评测/压测这类机器调用不该产生会话记录：带 X-No-Persist: 1 就整段跳过。
        // 不跳过的后果是——每跑一次 118 题的评测就往 qa_session 灌 118 条"匿名会话"，
        // 把真人对话历史淹掉（管理员视角会看到一屏同名会话）。
        boolean noPersistFlag = noPersist != null
                && (noPersist.equals("1") || noPersist.equalsIgnoreCase("true"));
        if (noPersistFlag) {
            QaService.QaAnswer plain = qaService.ask(question, topK, List.of());
            return ResponseEntity.ok()
                    .header("X-Persisted", "none")
                    .body(plain);
        }

        // 先定会话：没给就新建；给了就必须是"自己的"（管理员例外），否则按不存在拒绝
        long sessionId = resolveSession(req.sessionId(), question, userId,
                AuthService.ROLE_ADMIN.equals(role));

        // 取历史必须放在"本次提问入库"之前，取到的才是之前几轮而不是含自己
        List<ContextAssembler.Turn> history = contextAssembler.load(sessionDao, sessionId);

        QaService.QaAnswer answer = qaService.ask(question, topK, history);

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

    /** 调试：只看检索结果和拼好的提示词，不调模型；带 sessionId 时连多轮历史一起看 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("q") String q,
                                       @RequestParam(defaultValue = "3") int topK,
                                       @RequestParam(required = false) Long sessionId) {
        List<ContextAssembler.Turn> history = sessionId == null
                ? List.of()
                : contextAssembler.load(sessionDao, sessionId);
        return qaService.preview(q, Math.max(1, topK), history);
    }

    /**
     * 决定这次问答落在哪个会话里。
     *
     * <p>三种情况：
     * <ol>
     *   <li>没给 sessionId → 新建一个，归属人 = 当前登录人（未登录为 null 匿名）；</li>
     *   <li>给了且是自己的/管理员 → 追加到这个会话；</li>
     *   <li>给了别人的 → 按"不存在"拒绝（不静默新建，否则会悄悄吞掉一条记录）。</li>
     * </ol>
     */
    private long resolveSession(Long sessionId, String question, Long userId, boolean admin) {
        if (sessionId != null) {
            Map<String, Object> session = sessionDao.findById(sessionId);
            if (session != null) {
                Long owner = session.get("user_id") == null
                        ? null
                        : ((Number) session.get("user_id")).longValue();
                if (!SessionAccess.can(owner, userId, admin)) {
                    throw new IllegalArgumentException("会话不存在：" + sessionId);
                }
                return sessionId;
            }
            // 存在性检查通过但记录不存在：当作"没给"，新建（前端可能拿着被删的旧 id）
        }
        String title = question.length() > 20 ? question.substring(0, 20) + "…" : question;
        return sessionDao.create(title, SessionAccess.ownerFor(userId));
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
