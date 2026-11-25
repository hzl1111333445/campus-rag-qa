package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.QaService;
import com.campus.rag.service.RetrievalService;
import com.campus.rag.service.ContextAssembler;
import com.campus.rag.service.SessionAccess;
import com.campus.rag.service.SessionDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 流式生成专用的线程池。
     *
     * <p>为什么不能直接在这条请求里跑：SSE 是一条长连接，一次问答要占住线程 2~10 秒。
     * 如果直接跑在 Tomcat 的工作线程（默认 200 个，但同一时刻能接受的连接远少于此）上，
     * 十几个人同时问就会开始排队。这里用独立池 + 有限并发，把"慢"关在业务自己的笼子里。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "qa-stream");
        t.setDaemon(true);
        return t;
    });

    private final QaService qaService;
    private final SessionDao sessionDao;
    private final ContextAssembler contextAssembler;
    private final ObjectMapper objectMapper;
    private final com.campus.rag.service.BusinessMetrics metrics;

    public QaController(QaService qaService, SessionDao sessionDao,
                        ContextAssembler contextAssembler, ObjectMapper objectMapper, com.campus.rag.service.BusinessMetrics metrics) {
        this.metrics = metrics;
        this.qaService = qaService;
        this.sessionDao = sessionDao;
        this.contextAssembler = contextAssembler;
        this.objectMapper = objectMapper;
    }

    /** 请求体：{"question":"违纪处分有哪几种","topK":3,"sessionId":12} */
    public record AskRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 200, message = "最多 200 字")
            String question,
            @Min(value = 1, message = "至少 1 条")
            @Max(value = 10, message = "最多 10 条")
            Integer topK,
            Long sessionId) {
    }

    @PostMapping
    public ResponseEntity<QaService.QaAnswer> ask(
            @Valid @RequestBody AskRequest req,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
            @RequestAttribute(value = AuthInterceptor.ATTR_ROLE, required = false) String role,
            @RequestHeader(value = "X-No-Persist", required = false) String noPersist,
            jakarta.servlet.http.HttpServletResponse response) {
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
            metrics.qaFinished("sync", plain.gated(), plain.cached(), plain.elapsedMs(), plain.totalTokens());
            if (plain.gated()) {
                metrics.qaRefused("retrieval");
            }
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
        metrics.qaFinished("sync", answer.gated(), answer.cached(), answer.elapsedMs(), answer.totalTokens());
        if (answer.gated()) {
            metrics.qaRefused("retrieval");
        }

        // 存消息。存失败不影响本次回答——问答是主线，记账是副线，副线出错只记日志。
        long messageId = 0L;
        try {
            sessionDao.insertMessage(sessionId, "user", question,
                    false, null, null, null, null, null, null, null);
            messageId = sessionDao.insertMessage(sessionId, "assistant", answer.answer(),
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
                .header("X-Message-Id", String.valueOf(messageId))   // 前端拿它提交反馈
                .body(answer);
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>事件顺序是刻意设计的：
     * <pre>
     * event: meta     {sessionId, topK}                          —— 建/复用会话，前端先拿到 id
     * event: sources  {hits:[{n,title,chunkIndex,score}]}         —— 检索完成，先让用户看到"查到了什么"
     * event: token    {delta:"每"}   （若干次）                    —— 文字增量
     * event: done     {answer:..., references:[...], ttftMs, totalMs} —— 引用校验/接地校验结果
     * event: error    {message}                                   —— 出错（此时 HTTP 200 已经发出去，改不了状态码）
     * </pre>
     *
     * <p>为什么不复用 POST /api/qa：那里是"等整段生成完再返回"，拿不到中间过程；
     * 而且它的返回体结构已经被评测脚本依赖，改它就是改契约。这里新增一条路，老的保持不动。
     */
    @PostMapping("/stream")
    public SseEmitter stream(
            @Valid @RequestBody AskRequest req,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
            @RequestAttribute(value = AuthInterceptor.ATTR_ROLE, required = false) String role,
            @RequestHeader(value = "X-No-Persist", required = false) String noPersist,
            jakarta.servlet.http.HttpServletResponse response) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String question = req.question().trim();
        int topK = req.topK() == null ? 3 : Math.max(1, req.topK());
        boolean persist = !(noPersist != null
                && (noPersist.equals("1") || noPersist.equalsIgnoreCase("true")));

        // 会话与历史都在"开始推流之前"确定好，这样出错能直接抛给全局异常处理器（状态码正常）
        long sessionId = 0L;
        List<ContextAssembler.Turn> history = List.of();
        if (persist) {
            sessionId = resolveSession(req.sessionId(), question, userId,
                    AuthService.ROLE_ADMIN.equals(role));
            history = contextAssembler.load(sessionDao, sessionId);
        }
        // 与同步接口保持一致：会话号同时放响应头（meta 事件里也有一份，头是为了和 /api/qa 对齐）
        if (persist && sessionId > 0) {
            response.setHeader("X-Session-Id", String.valueOf(sessionId));
        }
        final long sid = sessionId;
        final List<ContextAssembler.Turn> hist = history;

        SseEmitter emitter = new SseEmitter(0L);   // 0 = 不超时，由客户端断开决定生命周期
        streamExecutor.submit(() -> runStream(emitter, question, topK, sid, hist, persist));
        return emitter;
    }

    private void runStream(SseEmitter emitter, String question, int topK, long sessionId,
                           List<ContextAssembler.Turn> history, boolean persist) {
        long begin = System.currentTimeMillis();
        AtomicLong firstTokenAt = new AtomicLong(0);
        try {
            emitter.send(event("meta", Map.of("sessionId", sessionId, "topK", topK)));

            QaService.QaAnswer answer = qaService.stream(question, topK, history,
                    hits -> sendSources(emitter, hits),
                    delta -> {
                        if (firstTokenAt.compareAndSet(0, System.currentTimeMillis())) {
                            log.info("流式首字：{}ms", firstTokenAt.get() - begin);
                        }
                        send(emitter, "token", Map.of("delta", delta));
                    });

            // 先落库再发 done：前端要拿到 messageId 才能提交反馈（差几毫秒，值得）
            metrics.qaFinished("stream", answer.gated(), answer.cached(), answer.elapsedMs(), answer.totalTokens());
        if (answer.gated()) {
            metrics.qaRefused("retrieval");
        }
        long messageId = persist ? saveMessages(sessionId, question, answer) : 0L;

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("answer", answer);
            done.put("promptTokens", answer.promptTokens());
            done.put("completionTokens", answer.completionTokens());
            done.put("totalTokens", answer.totalTokens());
            done.put("costYuan", answer.costYuan());
            done.put("messageId", messageId);
            long ttft = firstTokenAt.get() > 0 ? firstTokenAt.get() - begin : 0;
            done.put("ttftMs", ttft);
            done.put("totalMs", System.currentTimeMillis() - begin);
            send(emitter, "done", done);
            emitter.complete();
        } catch (Exception e) {
            log.warn("流式问答失败：{}", e.toString());
            try {
                send(emitter, "error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
            } catch (Exception ignored) {
                // 客户端已经断开，发不出去就算了
            }
            emitter.complete();
        }
    }

    /** 检索结果先发出去：把 n / 文档名 / 块号 / 分数给前端，让用户先看到"查到了这几条" */
    private void sendSources(SseEmitter emitter, List<RetrievalService.Hit> hits) {
        List<Map<String, Object>> list = new ArrayList<>();
        int n = 0;
        for (RetrievalService.Hit h : hits) {
            n++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", n);
            m.put("documentTitle", h.documentTitle());
            m.put("chunkIndex", h.chunkIndex());
            m.put("score", h.score());
            list.add(m);
        }
        send(emitter, "sources", Map.of("hits", list, "count", list.size()));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // 客户端断开：往上抛，交给 runStream 收尾
        }
    }

    private SseEmitter.SseEventBuilder event(String name, Object data) {
        return SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
    }

    /** 把一问一答落库（流式路径也复用，保证历史和普通问答一致） */
    /** 存两条消息，返回 assistant 那条的自增 id（前端拿它提交反馈；失败返回 0）。 */
    private long saveMessages(long sessionId, String question, QaService.QaAnswer answer) {
        try {
            sessionDao.insertMessage(sessionId, "user", question,
                    false, null, null, null, null, null, null, null);
            return sessionDao.insertMessage(sessionId, "assistant", answer.answer(),
                    answer.gated(), answer.citationOk(), answer.grounded(), answer.factCount(),
                    answer.topScore(), answer.elapsedMs(), toJson(answer.references()),
                    joinFacts(answer.missingFacts()));
        } catch (Exception e) {
            log.warn("保存会话消息失败 sessionId={}: {}", sessionId, e.toString());
            return 0L;
        }
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
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
