package com.campus.rag.service;

import com.campus.rag.config.LlmProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调 DeepSeek 的对话接口（OpenAI 兼容格式）。
 *
 * <p>请求：
 * <pre>
 * POST https://api.deepseek.com/v1/chat/completions
 * {"model":"deepseek-chat","messages":[{"role":"system","content":"..."},{"role":"user","content":"..."}],
 *  "temperature":0.2,"max_tokens":800}
 * </pre>
 *
 * <p>返回：
 * <pre>
 * {"choices":[{"message":{"role":"assistant","content":"回答内容"}}]}
 * </pre>
 */
@Service
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    private final LlmProperties props;
    private final RestClient http;

    public ChatClient(LlmProperties props) {
        this.props = props;

        // 连接超时 10 秒；读超时给久一点，模型有时候要算十几秒
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));

        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String model() {
        return props.getModel();
    }

    /**
     * 发一组消息，拿回模型生成的文字。
     *
     * @param messages 形如 [{role:"system",content:"你是..."},{role:"user",content:"资料...提问..."}]
     */
    public String chat(List<Map<String, String>> messages) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("campus.llm.api-key 是空的，请在 application-local.yml 里填 DeepSeek 的 key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", false);   // 先不上流式，跑通再说

        ChatResponse resp;
        try {
            resp = http.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("调用大模型失败：" + e.getStatusCode() + " "
                    + explain(e.getResponseBodyAsString()));
        }

        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            throw new IllegalStateException("大模型返回了空结果");
        }
        String content = resp.choices().get(0).message().content();
        log.debug("大模型返回 {} 字，模型 {}", content == null ? 0 : content.length(), props.getModel());
        return content == null ? "" : content;
    }

    private static String explain(String body) {
        String s = body == null ? "" : body;
        String b = s.toLowerCase();
        if (b.contains("401") || b.contains("authentication")) {
            return "API Key 不对或已失效";
        }
        if (b.contains("402") || b.contains("insufficient") || b.contains("balance")) {
            return "账户余额不足";
        }
        if (b.contains("429") || b.contains("rate")) {
            return "请求太频繁，被限流了";
        }
        if (b.contains("503") || b.contains("overload")) {
            return "模型服务繁忙，稍后重试";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ==================== 接口返回的数据结构 ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }
}
