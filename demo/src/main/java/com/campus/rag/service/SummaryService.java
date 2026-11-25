package com.campus.rag.service;

import com.campus.rag.config.ContextBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长会话记忆：把"最近几轮以前的旧消息"压成一条结构化摘要。
 *
 * <p><b>为什么需要</b>：{@link ContextAssembler} 只带最近 3 轮，第 4 轮起就看不见前面说过的话。
 * 实测（{@code tools/eval_multiturn.py}，5 场景 × 20 轮）：第 5 轮 5/5 记得，
 * 第 11 轮起 0/9——不是"偶发丢一句"，而是硬失忆。
 *
 * <p><b>为什么是"结构化摘要"而不是"一段话摘要"</b>：一段话摘要会被模型越压越虚，而且
 * 一旦把规章事实写进摘要，就产生了**没有出处的断言**，直接破坏"答案可溯源"这个契约。
 * 所以摘要只存三样东西：
 * <ul>
 *   <li>{@code goal}：用户想办什么事（一句话）</li>
 *   <li>{@code facts}：**用户自述**的客观情况（大一 / 绩点3.6 / 已过困难认定）</li>
 *   <li>{@code open}：还没解决、用户还在等答案的问题</li>
 * </ul>
 * 明确<b>禁止</b>摘录任何规章制度条文、处分档位、金额、期限——那些必须每轮重新检索。
 *
 * <p><b>为什么合并是确定性的</b>：不做"摘要的摘要"（递归摘要会丢信息）。
 * 每次只让模型抽新增片段的要点，再与旧摘要做<b>按值的并集去重</b>，不重新润色旧内容。
 *
 * <p><b>为什么异步</b>：摘要要调一次模型（约 0.5~1.5s）。放在主链路里会让首字延迟翻倍，
 * 而用户并不需要"这一轮就带上摘要"。失败只记 warn——绝不因为摘要失败影响回答。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    /** 抽取提示词：只抽"用户自述情况与诉求"，严禁摘录规定内容。 */
    private static final String EXTRACT_SYSTEM = """
            你是校园问答系统的「会话要点抽取器」。输入是一段多轮对话，输出一个 JSON 对象。
            规则：
            1. 只抽取用户自己说过的、与学校事务相关的内容（身份/年级/专业/成绩/奖助/违纪/住宿/诉求/进度）。
            2. 严禁摘录任何规章制度条文、处分档位、金额、时间期限等"规定性内容"——
               那些必须每次从知识库重新检索，写进摘要会造成没有出处的断言。
            3. goal：用户想办的事，一句话，不超过 20 字。
            4. facts：用户自述的客观情况，每条不超过 16 字，最多 6 条。
            5. open：用户还在等答案或尚未解决的问题，每条不超过 16 字，最多 4 条。
            6. 没有内容就写空字符串或空数组。只输出 JSON，不要解释，不要加代码块标记。
            输出格式：{"goal":"","facts":[],"open":[]}
            """;

    private final SummaryDao summaryDao;
    private final SessionDao sessionDao;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ContextBudget budget;

    /** 单线程：折叠是低频操作（每 5~10 轮一次），串行反而能避免同会话并发折叠。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qa-summary");
        t.setDaemon(true);
        return t;
    });

    public SummaryService(SummaryDao summaryDao, SessionDao sessionDao,
                          ChatClient chatClient, ObjectMapper objectMapper, ContextBudget budget) {
        this.summaryDao = summaryDao;
        this.sessionDao = sessionDao;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.budget = budget;
    }

    /** 启动时打一行预算表，方便排查"到底有没有生效"。 */
    public String describe() {
        return budget.describe();
    }

    @jakarta.annotation.PostConstruct
    void logBudget() {
        log.info("长会话摘要预算：{}", describe());
    }

    public boolean enabled() {
        return budget.summaryEnabled;
    }

    /**
     * 渲染成给提示词用的一段文字；没有摘要或未开启时返回空串。
     *
     * <p>每次问答都会调一次（单行主键查询，成本可忽略）。
     */
    public String render(long sessionId) {
        if (!budget.summaryEnabled) {
            return "";
        }
        try {
            Map<String, Object> row = summaryDao.find(sessionId);
            if (row == null) {
                return "";
            }
            return render(objectMapper.readTree(String.valueOf(row.get("content"))));
        } catch (Exception e) {
            log.warn("读摘要失败 sessionId={}: {}", sessionId, e.toString());
            return "";
        }
    }

    /** 异步折叠入口：问答落库之后调用，不阻塞响应。 */
    public void foldAsync(long sessionId) {
        if (!budget.summaryEnabled || sessionId <= 0) {
            return;
        }
        worker.submit(() -> {
            try {
                fold(sessionId);
            } catch (Exception e) {
                log.warn("折叠摘要失败 sessionId={}: {}", sessionId, e.toString());
            }
        });
    }

    /**
     * 真正的折叠：挑出"保护带以外、还没折过的消息"，抽要点并与旧摘要合并。
     *
     * <p>触发条件（三个都不满足就什么都不做）：
     * ①消息条数 ≥ {@code minMessagesToFold} ②可折片段字数 ≥ {@code minFoldChars}
     * ③（占比/增长/紧急）三者之一成立——见 {@link ContextBudget} 的注释。
     */
    void fold(long sessionId) throws Exception {
        List<Map<String, Object>> all = sessionDao.listMessages(sessionId);
        if (all.size() < budget.minMessagesToFold) {
            log.debug("跳过折叠 sessionId={} 消息{}条 < 最小{}条", sessionId, all.size(), budget.minMessagesToFold);
            return;
        }
        // 保护带：最近 keepRecentTurns 轮（= turns*2 条）永远留原文
        int keep = budget.keepRecentTurns * 2;
        int foldableEnd = all.size() - keep;
        if (foldableEnd <= 0) {
            log.debug("跳过折叠 sessionId={} 全在保护带内（{}条 ≤ {}条）", sessionId, all.size(), keep);
            return;
        }

        Map<String, Object> old = summaryDao.find(sessionId);
        long upto = old == null ? 0L : ((Number) old.get("upto_message_id")).longValue();

        List<Map<String, Object>> fresh = new ArrayList<>();
        for (int i = 0; i < foldableEnd; i++) {
            Map<String, Object> row = all.get(i);
            long id = ((Number) row.get("id")).longValue();
            if (id > upto) {
                fresh.add(row);
            }
        }
        if (fresh.isEmpty()) {
            log.debug("跳过折叠 sessionId={} 保护带外没有新消息（upto={}）", sessionId, upto);
            return;
        }

        StringBuilder seg = new StringBuilder();
        int segChars = 0;
        long lastId = upto;
        for (Map<String, Object> row : fresh) {
            String content = String.valueOf(row.getOrDefault("content", "")).replaceAll("\\s+", " ").trim();
            if (content.isEmpty()) {
                continue;
            }
            String line = ("assistant".equals(String.valueOf(row.get("role"))) ? "助手：" : "用户：") + content;
            if (segChars + line.length() > budget.maxSegmentChars) {
                break;
            }
            seg.append(line).append('\n');
            segChars += line.length();
            lastId = ((Number) row.get("id")).longValue();
        }
        if (segChars < budget.minFoldChars) {
            log.debug("跳过折叠 sessionId={} 片段{}字 < 最小{}字", sessionId, segChars, budget.minFoldChars);
            return;
        }

        // 触发判断看的是"整段历史占了多少"，不是"这一批要折多少"。
        // （2026-11-25 修正：原实现拿 foldable 片段去比窗口，导致 20 轮的会话也永远不触发）
        int historyChars = 0;
        for (Map<String, Object> row : all) {
            historyChars += String.valueOf(row.getOrDefault("content", "")).length();
        }
        int usedTokens = (int) Math.ceil(historyChars * 0.7);   // 与 ChatClient.estimate 的中文口径一致
        boolean emergency = usedTokens >= budget.windowTokens * budget.emergencyPct;
        boolean shouldFold = emergency
                || (usedTokens >= budget.windowTokens * budget.triggerPct && usedTokens >= budget.growthTokens);
        if (!shouldFold) {
            log.debug("跳过折叠 sessionId={} 历史={}字≈{}token 待折片段={}字",
                    sessionId, historyChars, usedTokens, segChars);
            return;
        }

        ObjectNode merged = merge(old == null ? null : String.valueOf(old.get("content")), extract(seg.toString()));
        // 库里存 JSON（不是渲染后的文字）：下次折叠要在此基础上做并集，
        // 渲染成文字后再解析会失败 → facts 每次被清空（2026-11-25 修）
        String json = merged.toString();
        String text = render(merged);
        if (text.length() > budget.summaryMaxChars) {
            text = text.substring(0, budget.summaryMaxChars) + "…";
        }
        int tokens = ChatClient.estimate(text);
        summaryDao.upsert(sessionId, lastId, json, tokens);
        summaryDao.markFolded(sessionId, lastId);
        log.info("已折叠会话摘要 sessionId={} 覆盖到消息{} 片段{}字 摘要{}字 {}token",
                sessionId, lastId, segChars, text.length(), tokens);
    }

    /** 让模型抽要点；解析失败就返回空对象（宁可没摘要，也不要坏摘要）。 */
    private JsonNode extract(String segment) {
        try {
            String raw = chatClient.chat(List.of(
                    Map.of("role", "system", "content", EXTRACT_SYSTEM),
                    Map.of("role", "user", "content", "对话片段：\n" + segment)));
            return objectMapper.readTree(stripFence(raw));
        } catch (Exception e) {
            log.warn("抽取要点失败: {}", e.toString());
            return objectMapper.createObjectNode();
        }
    }

    private static String stripFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        int i = s.indexOf('{');
        int j = s.lastIndexOf('}');
        return (i >= 0 && j > i) ? s.substring(i, j + 1) : "{}";
    }

    /**
     * 合并：goal 用最新的，facts/open 做并集去重后按上限截断。
     *
     * <p>刻意不重新润色旧内容——递归润色是摘要失真的主要来源。
     */
    private ObjectNode merge(String oldJson, JsonNode fresh) {
        ObjectNode out = objectMapper.createObjectNode();
        JsonNode old = null;
        try {
            old = oldJson == null ? null : objectMapper.readTree(oldJson);
        } catch (Exception ignored) {
            // 旧摘要坏了就当没有
        }

        String goal = text(fresh, "goal");
        if (goal.isEmpty()) {
            goal = text(old, "goal");
        }
        out.put("goal", goal);
        out.set("facts", union(arr(old, "facts"), arr(fresh, "facts"), 6));
        ArrayNode freshOpen = arr(fresh, "open");
        out.set("open", freshOpen.size() > 0 ? union(freshOpen, objectMapper.createArrayNode(), 4) : arr(old, "open"));
        return out;
    }

    private ArrayNode union(ArrayNode a, ArrayNode b, int max) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : a) {
            add(seen, n.asText(), max);
        }
        for (JsonNode n : b) {
            add(seen, n.asText(), max);
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (String s : seen) {
            out.add(s);
        }
        return out;
    }

    private static void add(Set<String> set, String v, int max) {
        String s = v == null ? "" : v.replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || set.contains(s) || set.size() >= max) {
            return;
        }
        set.add(s.length() > 16 ? s.substring(0, 16) : s);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return "";
        }
        String s = node.get(field).asText("").replaceAll("\\s+", " ").trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private ArrayNode arr(JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isArray()) {
            return (ArrayNode) node.get(field);
        }
        return objectMapper.createArrayNode();
    }

    /** 把结构化摘要渲染成一行字，塞进提示词。 */
    private String render(JsonNode node) {
        if (node == null) {
            return "";
        }
        List<String> parts = new ArrayList<>(3);
        String goal = text(node, "goal");
        if (!goal.isEmpty()) {
            parts.add("用户目标：" + goal);
        }
        ArrayNode facts = arr(node, "facts");
        if (facts.size() > 0) {
            List<String> vs = new ArrayList<>();
            facts.forEach(n -> vs.add(n.asText()));
            parts.add("自述情况：" + String.join("、", vs));
        }
        ArrayNode open = arr(node, "open");
        if (open.size() > 0) {
            List<String> vs = new ArrayList<>();
            open.forEach(n -> vs.add(n.asText()));
            parts.add("待解决：" + String.join("；", vs));
        }
        return String.join("；", parts);
    }

    /** 管理/调试用：清掉某会话摘要，下次重新从原文折。 */
    public int reset(long sessionId) {
        return summaryDao.deleteBySession(sessionId);
    }
}
