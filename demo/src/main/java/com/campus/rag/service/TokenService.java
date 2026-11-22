package com.campus.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手写的签名令牌（形如 {@code payloadBase64.signatureBase64}）。
 *
 * <p>为什么不用 JWT 库：这个项目从 BM25、RRF 到切分器都是手写的，目的是"每一步都能讲清楚原理"。
 * 令牌的本质就两句话：
 * <ol>
 *   <li>把「谁、什么角色、什么时候过期」写成 JSON；</li>
 *   <li>用只有服务端知道的密钥对这段 JSON 做 HMAC-SHA256，把签名附在后面。</li>
 * </ol>
 * 客户端能读内容（所以别放密码），但改任何一个字符签名就对不上 —— 这就是"防篡改"。
 *
 * <p><b>生产上不要这样手写</b>：应该用成熟库（含刷新令牌、密钥轮换、吊销名单）。
 * 这里手写是为了教学与零依赖，风险点我在代码里标出来了。
 */
@Service
public class TokenService {

    private final ObjectMapper mapper;
    private final byte[] secret;
    private final long expireMinutes;

    public TokenService(ObjectMapper mapper,
                        @Value("${campus.auth.secret:dev-only-change-me-in-production}") String secret,
                        @Value("${campus.auth.expire-minutes:720}") long expireMinutes) {
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireMinutes = expireMinutes;
    }

    /** 签发：带上 userId、username、role 和过期时间。 */
    public String issue(long userId, String username, String role) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uid", userId);
        payload.put("u", username);
        payload.put("r", role);
        payload.put("exp", System.currentTimeMillis() / 1000 + expireMinutes * 60);

        String body = toJson(payload);
        String encoded = b64(body.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    /**
     * 校验：签名不对、结构不对、已过期 → 返回 null（不抛异常，调用方好分支）。
     *
     * @return 成功返回 payload（含 uid / u / r），失败返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String encoded = token.substring(0, dot);
        String signature = token.substring(dot + 1);

        // 用常量时间比较，避免"按字符逐位试"的时序攻击（教学点：很多手写实现会漏这句）
        if (!constantTimeEquals(sign(encoded), signature)) {
            return null;
        }

        try {
            String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            Map<String, Object> payload = mapper.readValue(json, Map.class);
            long exp = ((Number) payload.getOrDefault("exp", 0)).longValue();
            if (exp * 1000 < System.currentTimeMillis()) {
                return null;
            }
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return b64(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败：" + e.getMessage(), e);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("令牌序列化失败：" + e.getMessage(), e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
