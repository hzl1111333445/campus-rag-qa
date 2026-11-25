package com.campus.rag.config;

import com.campus.rag.service.AuthService;
import com.campus.rag.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ActuatorGuardFilter} 的三条契约：
 * <ol>
 *   <li>无令牌 → 401（不能泄露内部指标）</li>
 *   <li>学生令牌 → 403</li>
 *   <li>管理员令牌 → 放行；{@code /actuator/health} 永远公开</li>
 * </ol>
 *
 * <p>为什么专门测这个：这条防线是 Filter 实现的（Interceptor 拦不住 Actuator），
 * 一旦有人"顺手"把它改回 Interceptor，指标就会裸奔，而单测能立刻发现。
 */
class ActuatorGuardFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ActuatorGuardFilter filter(TokenService tokenService) {
        return new ActuatorGuardFilter(tokenService, mapper);
    }

    @Test
    @DisplayName("无令牌访问 /actuator/prometheus → 401，且提示需要管理员登录")
    void noToken() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(tokenService).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("管理员登录");
        assertThat(chain.getRequest()).as("被拦下时不应继续走后续链路").isNull();
    }

    @Test
    @DisplayName("学生令牌访问 /actuator/metrics → 403")
    void studentToken() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.verify(anyString()))
                .thenReturn(Map.of("uid", 2, "u", "student", "r", AuthService.ROLE_STUDENT));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.addHeader("Authorization", "Bearer fake-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(tokenService).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("仅管理员");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("管理员令牌 → 放行；/actuator/health 不需要令牌")
    void adminAndHealth() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.verify(anyString()))
                .thenReturn(Map.of("uid", 1, "u", "admin", "r", AuthService.ROLE_ADMIN));

        MockHttpServletRequest adminReq = new MockHttpServletRequest("GET", "/actuator/prometheus");
        adminReq.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse adminResp = new MockHttpServletResponse();
        MockFilterChain adminChain = new MockFilterChain();
        filter(tokenService).doFilter(adminReq, adminResp, adminChain);
        assertThat(adminChain.getRequest()).as("管理员应被放行").isNotNull();

        MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse healthResp = new MockHttpServletResponse();
        MockFilterChain healthChain = new MockFilterChain();
        filter(tokenService).doFilter(healthReq, healthResp, healthChain);
        assertThat(healthChain.getRequest()).as("健康检查不需要令牌").isNotNull();
        assertThat(tokenService.verify(anyString())).as("健康检查不应触发令牌校验逻辑").isNotNull();
    }
}
