package com.campus.rag.config;

import com.campus.rag.service.AuthException;
import com.campus.rag.service.ForbiddenException;
import com.campus.rag.service.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常 → HTTP 状态码的映射。
 *
 * <p>为什么需要它：Spring Boot 默认会把未处理异常变成 500 Internal Server Error，
 * 而"密码错了""文档不存在"这类情况根本不是服务器故障。给前端一个准确的状态码，
 * 前端才能决定是"提示重试"还是"跳去登录页"。
 *
 * <p>优先级：更具体的异常类型先匹配，最后才是兜底 Exception。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 登录失败 / 令牌无效 → 401 */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(401, e.getMessage()));
    }

    /** 越权访问 → 403（按 id 访问别人的会话时，Controller 会先转成 404 语义，不透出存在性） */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(403, e.getMessage()));
    }

    /** 参数不合法（文档不存在、问题为空等）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, e.getMessage()));
    }

    /**
     * 触发限流 → 429，并带 {@code Retry-After} 头。
     *
     * <p>为什么必须是 429 而不是 400 或 503：429 Too Many Requests 是标准语义，
     * 客户端（和网关）看到它就知道"该退避重试"，而不是"改参数"或"服务挂了"。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        Map<String, Object> body = body(429, e.getMessage());
        body.put("limit", e.limit());
        body.put("retryAfterSeconds", e.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(body);
    }

    /**
     * 方法不被支持（比如对只支持 GET 的接口发 POST）→ 405。
     *
     * <p>这条是踩出来的：缓存统计接口只写了 {@code @GetMapping}，用 POST 调时
     * Spring 抛 {@code HttpRequestMethodNotSupportedException}，被下面的兜底处理器
     * 变成了 500"服务器内部错误"——明明是我调错了方法，却看起来像服务器坏了。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body(405, "该接口不支持 " + e.getMethod() + " 方法"));
    }

    /** 路径不存在 → 404（同理，不能报成 500） */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(404, "接口不存在"));
    }

    /** 兜底 → 500，并且把堆栈打进日志（前端只看到一句话，方便排查又不泄露内部细节） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常：{}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(500, "服务器内部错误：" + e.getClass().getSimpleName()));
    }

    private Map<String, Object> body(int status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("message", message);
        return map;
    }
}
