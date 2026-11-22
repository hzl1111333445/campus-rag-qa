package com.campus.rag.config;

import com.campus.rag.service.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    /** 参数不合法（文档不存在、问题为空等）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, e.getMessage()));
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
