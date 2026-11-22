package com.campus.rag.controller;

import com.campus.rag.config.AuthInterceptor;
import com.campus.rag.service.AuthService;
import com.campus.rag.service.SessionAccess;
import com.campus.rag.service.SessionDao;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 对话管理接口。
 *
 * <p>五个动作，正好对应前端左侧会话栏需要的全部操作：
 * <ul>
 *   <li>GET    /api/session                  —— 列出会话（最近的在前）</li>
 *   <li>POST   /api/session                  —— 新建会话</li>
 *   <li>PATCH  /api/session/{id}             —— 重命名</li>
 *   <li>DELETE /api/session/{id}             —— 删除（连带消息）</li>
 *   <li>GET    /api/session/{id}/messages    —— 打开某个会话时加载历史</li>
 * </ul>
 *
 * <p>提问本身还是走 POST /api/qa，会话 id 通过响应头 X-Session-Id 回传
 * （这样老接口的返回体结构不变，评测脚本 eval-answer.py 不用改）。
 *
 * <p><b>数据隔离（2026-11-23）</b>：所有接口都要登录（拦截器保证），
 * 且只能操作自己的会话 —— 判定规则统一在 {@link SessionAccess} 里，
 * 越权时一律回"会话不存在"，不告诉调用方"存在但你没权限"。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionDao sessionDao;

    public SessionController(SessionDao sessionDao) {
        this.sessionDao = sessionDao;
    }

    /** 新建会话的请求体：{"title":"旷课处分"}，title 可以不给。 */
    public record CreateRequest(String title) {
    }

    /** 重命名的请求体：{"title":"新名字"}。 */
    public record RenameRequest(String title) {
    }

    /**
     * 会话列表。
     *
     * <p>管理员传 null 给 DAO 表示"不过滤"（连匿名会话一起看，用于排查评测）；
     * 普通用户只查 user_id = 自己的，过滤发生在 SQL 里而不是查完再筛。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "30") int limit,
                                    @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId,
                                    @RequestAttribute(AuthInterceptor.ATTR_ROLE) String role) {
        int n = Math.min(Math.max(limit, 1), 100);
        Long filter = AuthService.ROLE_ADMIN.equals(role) ? null : userId;
        List<Map<String, Object>> rows = sessionDao.list(n, filter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("items", rows);
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateRequest req,
                                      @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId) {
        String title = req == null ? null : req.title();
        // 归属人 = 当前登录人，绝不从请求体里取 —— 请求体是可以伪造的
        long id = sessionDao.create(title, SessionAccess.ownerFor(userId));
        return sessionDao.findById(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> rename(@PathVariable long id, @RequestBody RenameRequest req,
                                      @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId,
                                      @RequestAttribute(AuthInterceptor.ATTR_ROLE) String role) {
        requireOwned(id, userId, role);
        int n = sessionDao.rename(id, req == null ? null : req.title());
        if (n == 0) {
            throw new IllegalArgumentException("会话不存在或标题为空：" + id);
        }
        return sessionDao.findById(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id,
                                      @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId,
                                      @RequestAttribute(AuthInterceptor.ATTR_ROLE) String role) {
        requireOwned(id, userId, role);
        int n = sessionDao.delete(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("deleted", n > 0);
        return out;
    }

    /** 打开历史会话：返回会话本身 + 全部消息（消息里带当年的引用快照）。 */
    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable long id,
                                        @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId,
                                        @RequestAttribute(AuthInterceptor.ATTR_ROLE) String role) {
        requireOwned(id, userId, role);
        Map<String, Object> session = sessionDao.findById(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session);
        out.put("messages", sessionDao.listMessages(id));
        return out;
    }

    /**
     * 校验这条会话属于当前请求者。
     *
     * <p>规则在 {@link SessionAccess}；这里只负责"越权 → 用不存在的口径回话"。
     * 为什么不是 403：403 等于承认"这个 id 存在，只是你不能看"，
     * 攻击者可以据此判断别人有多少条会话（枚举）。所以统一说"会话不存在"。
     */
    private void requireOwned(long id, long userId, String role) {
        Map<String, Object> session = sessionDao.findById(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在：" + id);
        }
        Long owner = session.get("user_id") == null ? null : ((Number) session.get("user_id")).longValue();
        boolean admin = AuthService.ROLE_ADMIN.equals(role);
        if (!SessionAccess.can(owner, userId, admin)) {
            throw new IllegalArgumentException("会话不存在：" + id);
        }
    }
}
