package com.campus.rag.config;

import com.campus.rag.service.CacheService;
import com.campus.rag.service.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 的契约：
 * <ol>
 *   <li>超过阈值 → 抛 {@link RateLimitException}（由全局异常处理器转成 429，含 limit 与 retryAfterSeconds）</li>
 *   <li>未超阈值 → 放行，并回写 {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining}</li>
 *   <li>带 {@code X-No-Persist} 的机器调用 → 完全不碰 Redis（不计数）</li>
 *   <li>Redis 不可用（返回 0）→ 放行（宁可少保护，不可把用户挡在门外）</li>
 * </ol>
 *
 * <p>用 Mock 而不是真连 Redis：这几条是"逻辑契约"，不该因为本机没起 Redis 就红。
 */
class RateLimitInterceptorTest {

    private final CacheService cacheService = mock(CacheService.class);

    private RateLimitInterceptor interceptor(int limit) {
        return new RateLimitInterceptor(cacheService, limit);
    }

    private MockHttpServletRequest request(String path, boolean machine) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, 7L);
        if (machine) {
            req.addHeader("X-No-Persist", "1");
        }
        return req;
    }

    @Test
    @DisplayName("第 31 次/分钟 → 抛 RateLimitException，带 limit 与剩余秒数")
    void overLimit() {
        when(cacheService.enabled()).thenReturn(true);
        when(cacheService.incrementWithWindow(anyString(), any(Duration.class))).thenReturn(31L);

        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor(30).preHandle(request("/api/qa", false), resp, new Object()))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("每分钟最多 30 次");

        RateLimitException e = (RateLimitException) org.assertj.core.api.Assertions
                .catchThrowable(() -> interceptor(30).preHandle(request("/api/qa", false), resp, new Object()));
        assertThat(e.limit()).isEqualTo(30);
        assertThat(e.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("未超阈值 → 放行并回写限流响应头")
    void underLimit() {
        when(cacheService.enabled()).thenReturn(true);
        when(cacheService.incrementWithWindow(anyString(), any(Duration.class))).thenReturn(5L);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean pass = interceptor(30).preHandle(request("/api/qa", false), resp, new Object());

        assertThat(pass).isTrue();
        assertThat(resp.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(resp.getHeader("X-RateLimit-Remaining")).isEqualTo("25");
    }

    @Test
    @DisplayName("带 X-No-Persist 的机器调用 → 不计数（不碰 CacheService）")
    void machineBypass() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean pass = interceptor(30).preHandle(request("/api/qa", true), resp, new Object());

        assertThat(pass).isTrue();
        verifyNoInteractions(cacheService);
    }

    @Test
    @DisplayName("Redis 不可用（计数返回 0）→ 放行")
    void redisDown() {
        when(cacheService.enabled()).thenReturn(true);
        when(cacheService.incrementWithWindow(anyString(), any(Duration.class))).thenReturn(0L);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(interceptor(30).preHandle(request("/api/qa", false), resp, new Object())).isTrue();
        assertThat(resp.getHeader("X-RateLimit-Limit")).as("降级时不该假装有限流").isNull();
    }
}
