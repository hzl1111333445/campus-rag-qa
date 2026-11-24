package com.campus.rag.service;

/**
 * 触发限流时抛出，由 {@code GlobalExceptionHandler} 映射成 429。
 *
 * <p>为什么单独一个异常类型，而不是复用 {@code IllegalArgumentException}（400）：
 * "你参数写错了"和"你请求太多了"是两件完全不同的事——前者应该改代码，
 * 后者应该等一会儿再来。前端也只有靠状态码区分"弹错误提示"还是"退避重试"。
 */
public class RateLimitException extends RuntimeException {

    private final int limit;
    private final long retryAfterSeconds;

    public RateLimitException(String message, int limit, long retryAfterSeconds) {
        super(message);
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int limit() {
        return limit;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
