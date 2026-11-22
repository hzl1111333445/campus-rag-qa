package com.campus.rag.service;

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

    /** 一条历史消息，role 只会是 user 或 assistant。 */
    public record Turn(String role, String content) {
    }

    private final boolean enabled;
    private final int turns;
    private final int maxCharsPerMsg;
    private final int maxTotalChars;

    public ContextAssembler(
            @Value("${campus.context.enabled:true}") boolean enabled,
            @Value("${campus.context.turns:3}") int turns,
            @Value("${campus.context.max-chars-per-msg:120}") int maxCharsPerMsg,
            @Value("${campus.context.max-total-chars:600}") int maxTotalChars) {
        this.enabled = enabled;
        this.turns = Math.max(0, turns);
        this.maxCharsPerMsg = Math.max(20, maxCharsPerMsg);
        this.maxTotalChars = Math.max(100, maxTotalChars);
    }

    /**
     * 从库里读这个会话的历史（不含本次提问——调用方要在把本次提问入库之前调）。
     */
    public List<Turn> load(SessionDao sessionDao, long sessionId) {
        if (!enabled) {
            return List.of();
        }
        List<Map<String, Object>> rows = sessionDao.listMessages(sessionId);
        return assemble(rows);
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
        int maxMsgs = turns * 2;   // 一轮 = 问 + 答
        List<Turn> picked = new ArrayList<>(maxMsgs);
        int used = 0;

        // 从最后一条往前拿，够数或超预算就停
        for (int i = rows.size() - 1; i >= 0 && picked.size() < maxMsgs; i--) {
            Map<String, Object> row = rows.get(i);
            String role = String.valueOf(row.getOrDefault("role", "user"));
            String content = stripCitations(oneLine(row.get("content")));
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > maxCharsPerMsg) {
                content = content.substring(0, maxCharsPerMsg) + "…";
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
