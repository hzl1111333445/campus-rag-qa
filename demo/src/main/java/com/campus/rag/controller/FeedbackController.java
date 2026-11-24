package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AuthException;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.FeedbackDao;
import com.campus.rag.service.ForbiddenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答反馈接口（v2.0 第一块）。
 *
 * <ul>
 *   <li>POST /api/feedback                —— 学生对某条回答点赞/点踩（可改主意）</li>
 *   <li>GET  /api/feedback/mine           —— 我在某条消息上的评价（前端回显已赞/已踩）</li>
 *   <li>GET  /api/feedback/page           —— 管理端列表（分页、可按 level 过滤）</li>
 *   <li>GET  /api/feedback/stats?days=7   —— 管理端统计（满意度、点踩原因、按天趋势）</li>
 * </ul>
 *
 * <p>权限：提交只要求登录且只能评自己的回答；列表和统计只给管理员（前端另有菜单控制，
 * 但真正的边界在后端——前端藏起来只是体验，不是安全）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    /** 缺陷白名单：只收这几个词，避免自由文本把聚合搞乱（多选，逗号分隔）。 */
    private static final List<String> PROBLEMS =
            List.of("不完整", "有错误", "答非所问", "没依据", "太啰嗦", "其他");

    private final FeedbackDao feedbackDao;

    public FeedbackController(FeedbackDao feedbackDao) {
        this.feedbackDao = feedbackDao;
    }

    /**
     * 一次评价。
     *
     * @param messageId 被评价的 assistant 消息 id（前端从 SSE 的 done 事件里拿到）
     * @param level     2=完全解决、1=部分解决、0=未解决；只收这三个值
     * @param problems  缺陷多选（逗号分隔），level=2 时忽略
     * @param comment   补充说明，最多 200 字
     */
    public record FeedbackRequest(Long messageId, Integer level, String problems, String comment) {
    }

    @PostMapping
    public Map<String, Object> submit(
            @RequestBody FeedbackRequest req,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId) {

        if (userId == null) {
            throw new AuthException("请先登录后再评价");
        }
        if (req.messageId() == null || req.messageId() <= 0) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        Integer level = req.level();
        if (level == null || level < FeedbackDao.LEVEL_UNSOLVED || level > FeedbackDao.LEVEL_SOLVED) {
            throw new IllegalArgumentException("level 只能是 2（完全解决）/ 1（部分解决）/ 0（未解决）");
        }
        // 越权一律回"消息不存在"：告诉调用方"存在但你没权限"等于泄露别人的会话 id 可枚举
        if (!feedbackDao.canRate(req.messageId(), userId)) {
            throw new IllegalArgumentException("消息不存在：" + req.messageId());
        }

        String problems = null;
        if (level < FeedbackDao.LEVEL_SOLVED) {
            // 完全解决不需要解释"缺什么"；部分解决/未解决才收缺陷
            problems = normalizeProblems(req.problems());
        }
        String comment = trim(req.comment());
        if (comment != null && comment.length() > 200) {
            comment = comment.substring(0, 200); // 表是 500，但前端只让填 200，接口兜一层
        }

        feedbackDao.upsert(req.messageId(), userId, level, problems, comment);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", req.messageId());
        out.put("level", level);
        out.put("problems", problems);
        return out;
    }

    @GetMapping("/mine")
    public Map<String, Object> mine(
            @RequestParam long messageId,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId) {
        if (userId == null) {
            throw new AuthException("请先登录");
        }
        Map<String, Object> row = feedbackDao.findMine(messageId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", messageId);
        out.put("feedback", row);
        return out;
    }

    @GetMapping("/page")
    public Map<String, Object> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer level,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
            @RequestAttribute(value = AuthInterceptor.ATTR_ROLE, required = false) String role) {

        requireAdmin(userId, role);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", feedbackDao.count(level));
        out.put("records", feedbackDao.page(level, s, (p - 1) * s));
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestParam(defaultValue = "7") int days,
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId,
            @RequestAttribute(value = AuthInterceptor.ATTR_ROLE, required = false) String role) {

        requireAdmin(userId, role);
        return feedbackDao.stats(Math.min(Math.max(days, 1), 90));
    }

    private void requireAdmin(Long userId, String role) {
        if (userId == null) {
            throw new AuthException("请先登录");
        }
        if (!AuthService.ROLE_ADMIN.equals(role)) {
            throw new ForbiddenException("该功能仅管理员可用");
        }
    }

    /**
     * 校验并规整缺陷多选：只留白名单里的词、去重、逗号分隔、总长不超 200。
     *
     * <p>过滤而不是报错：前端可能因为版本差异多传一个词，为此让整条评价失败不值得；
     * 但如果一个合法词都没有，就当没填。
     */
    private static String normalizeProblems(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,，]")) {
            String p = part.trim();
            if (!PROBLEMS.contains(p) || sb.indexOf(p) >= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            return null;
        }
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
