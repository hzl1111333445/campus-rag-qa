package com.campus.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 多轮对话的「上下文组装器」：决定带哪几条历史、每条留多长。
 *
 * <p><b>为什么需要单独一个类</b>：把"带多少历史"塞进 PromptBuilder 或 QaService 都能跑，
 * 但策略会散落在两处，调参要改代码。抽出来后，三条策略全在配置里：
 * <ul>
 *   <li>{@code campus.context.turns}（默认 3）：带最近几轮问答</li>
 *   <li>{@code campus.context.max-chars-per-msg}（默认 120）：单条历史截多长</li>
 *   <li>{@code campus.context.max-total-chars}（默认 600）：历史总字数上限</li>
 * </ul>
 *
 * <p><b>三条设计原则</b>（答辩可以直接讲）：
 * <ol>
 *   <li><b>从最近往前取</b>：越近的话越可能包含"那/它/上面说的"这类指代，越远越可能是别的话题</li>
 *   <li><b>双向截断</b>：单条截断防一条长回答吃满预算；总量截断防历史挤掉【资料】</li>
 *   <li><b>历史只是"理解指代"的线索，不是事实来源</b>：模型自己上一轮说的话也可能有错，
 *       如果拿它当依据，错误会一轮轮放大（这叫自污染）。所以提示词里明确写了历史不得引用，
 *       事实只能来自本轮检索到的【资料】</li>
 * </ol>
 */
@Component
public class ContextAssembler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextAssembler.class);

    /** 一条历史消息，role 只会是 user 或 assistant；role=summary 表示"更早对话的摘要"。 */
    public record Turn(String role, String content) {
    }

    private final boolean enabled;
    private final int turns;
    private final int maxCharsPerMsg;
    private final int maxTotalChars;

    /**
     * 是否把"助手的回答"也放进历史。
     *
     * <p>默认 <b>false</b>：2026-11-25 实测发现助手会把历史里自己说过的话当成"用户的情况"
     * 复述（同一句"你上学期有作弊记录"连续出现两次），自我强化比丢上下文更危险。
     * 需要时可以 CONTEXT_INCLUDE_ASSISTANT=true 打开，用同一套评测对比。
     */
    private final boolean includeAssistant;

    /** 长会话摘要（可为 null：单测里不注入）。 */
    private final SummaryService summaryService;

    @Autowired
    public ContextAssembler(
            @Value("${campus.context.enabled:true}") boolean enabled,
            @Value("${campus.context.turns:3}") int turns,
            @Value("${campus.context.max-chars-per-msg:120}") int maxCharsPerMsg,
            @Value("${campus.context.max-total-chars:600}") int maxTotalChars,
            @Value("${campus.context.include-assistant:false}") boolean includeAssistant,
            SummaryService summaryService) {
        this.enabled = enabled;
        this.turns = Math.max(0, turns);
        this.maxCharsPerMsg = Math.max(20, maxCharsPerMsg);
        this.maxTotalChars = Math.max(100, maxTotalChars);
        this.includeAssistant = includeAssistant;
        this.summaryService = summaryService;
    }

    /** 单测用的 4 参构造：不带摘要。 */
    ContextAssembler(boolean enabled, int turns, int maxCharsPerMsg, int maxTotalChars) {
        this(enabled, turns, maxCharsPerMsg, maxTotalChars, true, null);
    }

    /**
     * 从库里读这个会话的历史（不含本次提问——调用方要在把本次提问入库之前调）。
     *
     * <p>返回值可能以一条 {@code role=summary} 的"伪消息"开头：那是更早对话的要点摘要。
     * 用同一个列表承载，是为了让所有调用点（问答同步/流式/调试预览/Agent）不用改签名。
     */
    public List<Turn> load(SessionDao sessionDao, long sessionId) {
        if (!enabled) {
            return List.of();
        }
        List<Turn> dialog = assemble(sessionDao.listMessages(sessionId));
        if (summaryService == null) {
            return dialog;
        }
        String summary = summaryService.render(sessionId);
        if (summary == null || summary.isBlank()) {
            return dialog;
        }
        List<Turn> out = new ArrayList<>(dialog.size() + 1);
        out.add(new Turn("summary", summary));
        out.addAll(dialog);
        log.debug("注入会话摘要 sessionId={} 摘要={}字 原文{}条", sessionId, summary.length(), dialog.size());
        return List.copyOf(out);
    }

    /**
     * 纯函数版本：从数据库行里挑出最近的消息并截断。
     *
     * <p>抽成纯函数是为了能单测——不用连 MySQL 就能验证"截断对不对、顺序对不对"。
     */
    public List<Turn> assemble(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || turns <= 0) {
            return List.of();
        }
        int maxMsgs = includeAssistant ? turns * 2 : turns;   // 一轮 = 问 + 答；只要问就一条一轮
        List<Turn> picked = new ArrayList<>(maxMsgs);
        int used = 0;

        // 从最后一条往前拿，够数或超预算就停
        for (int i = rows.size() - 1; i >= 0 && picked.size() < maxMsgs; i--) {
            Map<String, Object> row = rows.get(i);
            String role = String.valueOf(row.getOrDefault("role", "user"));
            if (!includeAssistant && !"user".equals(role)) {
                continue;   // 只要用户问过什么，不要助手说过什么（防自我强化）
            }
            String content = stripCitations(oneLine(row.get("content")));
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > maxCharsPerMsg) {
                content = clip(content, maxCharsPerMsg);
            }
            // 预算不够就整条不要，不要半条——半条历史比没有更误导
            if (used + content.length() > maxTotalChars) {
                break;
            }
            picked.add(new Turn(role, content));
            used += content.length();
        }

        Collections.reverse(picked);   // 恢复成"时间正序"，模型读起来才自然
        return List.copyOf(picked);
    }

    /**
     * 单条截断：<b>头 60% + 尾 40%</b>，中间用省略号。
     *
     * <p>为什么不能只留头（2026-11-25 修）：实测 81 条助手回答里 **57% 超过 120 字**，
     * 而我们的回答格式是"先结论 + 分条说明 + 最后一句兜底/建议"，期限、处分档位、
     * "具体以某部门为准"这类关键信息经常落在后半段。只截头等于系统性丢掉结论尾部。
     *
     * <p>为什么不各留 50%：开头包含"结论 + 第 1 条"，信息密度更高；
     * 尾部只需要兜住最后一句的条件/建议，40% 足够。
     */
    static String clip(String content, int max) {
        if (content == null || content.length() <= max) {
            return content;
        }
        int head = (int) Math.round(max * 0.6);
        int tail = Math.max(1, max - head);
        return content.substring(0, head) + "…" + content.substring(content.length() - tail);
    }

    /**
     * 去掉历史里的引用编号（如 [1] [3]）。
     *
     * <p>为什么必须去：上一轮的答案里带着 [1]，模型很容易顺手把 [1] 抄到本轮答案里。
     * 而本轮的 [1] 恰好也是合法编号（资料永远从 1 开始），校验发现不了——
     * 于是"编号合法、出处是错的"，比明显的幻觉更难查。历史只用来理解指代，
     * 本来就不该带任何可被引用成事实的东西。
     */
    private static String stripCitations(String content) {
        return content.replaceAll("\\[\\d{1,2}]", "").replaceAll("\\s+", " ").trim();
    }

    /** 把多行文本压成一行：历史里的换行会把提示词结构搅乱。 */
    private static String oneLine(Object raw) {
        if (raw == null) {
            return "";
        }
        return String.valueOf(raw).replaceAll("\\s+", " ").trim();
    }

    /** 给日志/调试用：看看这次到底带了什么历史。 */
    public static String describe(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (Turn t : history) {
            sb.append("assistant".equals(t.role()) ? "A: " : "U: ").append(t.content()).append(" | ");
        }
        return sb.toString();
    }
}
