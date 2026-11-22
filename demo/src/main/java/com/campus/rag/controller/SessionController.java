package com.campus.rag.controller;

import com.campus.rag.service.SessionDao;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "30") int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> rows = sessionDao.list(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("items", rows);
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateRequest req) {
        String title = req == null ? null : req.title();
        long id = sessionDao.create(title);
        return sessionDao.findById(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> rename(@PathVariable long id, @RequestBody RenameRequest req) {
        int n = sessionDao.rename(id, req == null ? null : req.title());
        if (n == 0) {
            throw new IllegalArgumentException("会话不存在或标题为空：" + id);
        }
        return sessionDao.findById(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        int n = sessionDao.delete(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("deleted", n > 0);
        return out;
    }

    /** 打开历史会话：返回会话本身 + 全部消息（消息里带当年的引用快照）。 */
    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable long id) {
        Map<String, Object> session = sessionDao.findById(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session);
        out.put("messages", sessionDao.listMessages(id));
        return out;
    }
}
