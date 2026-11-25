package com.campus.rag.service;

import com.campus.rag.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    /** 流式用的客户端：JDK 自带 HttpClient，支持边下边读（RestClient 的流式要另配 converter，太重） */
    private final HttpClient streamHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;

    public ChatClient(LlmProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

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
     * 发一组消息，拿回模型生成的文字（不带用量）。
     *
     * @param messages 形如 [{role:"system",content:"你是..."},{role:"user",content:"资料...提问..."}]
     */
    public String chat(List<Map<String, String>> messages) {
        return chatWithUsage(messages).content();
    }

    /**
     * 一次对话的结果：正文 + 用量。
     *
     * <p>用量优先取上游返回的 {@code usage}（DeepSeek/OpenAI 兼容格式都有）；
     * 上游没给时按"中文 1 字 ≈ 0.7 token"粗估，并在日志里标注，避免把估算当账。
     */
    public record ChatResult(String content, int promptTokens, int completionTokens, boolean estimated) {

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * 发一组消息，拿回正文与用量。Agent 循环用它记账、控预算。
     */
    public ChatResult chatWithUsage(List<Map<String, String>> messages) {
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
        String text = content == null ? "" : content;

        int promptTokens;
        int completionTokens;
        boolean estimated;
        if (resp.usage() != null && resp.usage().prompt_tokens() != null
                && resp.usage().completion_tokens() != null) {
            promptTokens = resp.usage().prompt_tokens();
            completionTokens = resp.usage().completion_tokens();
            estimated = false;
        } else {
            promptTokens = estimateMessages(messages);
            completionTokens = estimate(text);
            estimated = true;
            log.debug("上游未返回 usage，按字数估算 prompt={} completion={}", promptTokens, completionTokens);
        }
        log.debug("大模型返回 {} 字、{} token（估算={}），模型 {}",
                text.length(), promptTokens + completionTokens, estimated, props.getModel());
        return new ChatResult(text, promptTokens, completionTokens, estimated);
    }

    /** 粗估：中文 1 字约 0.7 token，代码/英文约 0.3 token/字符；只用于"没给 usage 时的兜底"和预算预判。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                ascii++;
            } else {
                cjk++;
            }
        }
        return (int) Math.ceil(cjk * 0.7 + ascii * 0.3);
    }

    /** 估算一组消息的 prompt token（供 Agent 预算预判）。 */
    public static int estimateMessages(List<Map<String, String>> messages) {
        int sum = 0;
        for (Map<String, String> m : messages) {
            sum += estimate(m.get("content")) + 4;   // 每条消息的角色/分隔符约 4 token
        }
        return sum;
    }

    /**
     * 流式生成：每收到一小段文字就回调一次。
     *
     * <p><b>为什么单独用 JDK 的 HttpClient，而不复用上面的 RestClient？</b>
     * 上面那套是"发请求 → 等完整响应体 → 反序列化"，天然拿不到中间过程。
     * 流式要的是"响应体还没结束就开始读"，用 {@code BodyHandlers.ofInputStream()} 拿到字节流逐行解析最直接，
     * 不用为了一个接口把整个客户端换成 WebFlux。
     *
     * <p>上游是 OpenAI 兼容格式，每一行形如：
     * <pre>
     * data: {"choices":[{"delta":{"content":"每"}}]}
     * data: {"choices":[{"delta":{"content":"学期"}}]}
     * data: [DONE]
     * </pre>
     *
     * @param onToken 每收到一段文字回调一次（可能一次几个字，也可能一次一句）
     * @return 拼接后的完整回答（和 {@link #chat} 的返回值等价，方便上层继续做引用校验）
     */
    public String chatStream(List<Map<String, String>> messages, Consumer<String> onToken) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("campus.llm.api-key 是空的，请在 application-local.yml 里填 DeepSeek 的 key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", true);

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("请求体序列化失败：" + e.getMessage());
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalStateException("流式请求构造失败：" + e.getMessage());
        }

        StringBuilder answer = new StringBuilder();
        try {
            HttpResponse<InputStream> response = streamHttp.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("调用大模型失败：" + response.statusCode() + " " + explain(err));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;   // 空行是分隔符，冒号开头是注释/心跳
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    String delta = deltaOf(payload);
                    if (delta != null && !delta.isEmpty()) {
                        answer.append(delta);
                        onToken.accept(delta);
                    }
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式调用被中断");
        } catch (Exception e) {
            throw new IllegalStateException("流式调用失败：" + e.getMessage());
        }

        log.debug("流式返回 {} 字，模型 {}", answer.length(), props.getModel());
        return answer.toString();
    }

    /** 从一帧增量里取出文字；解析不了就返回 null（不因为一条坏帧把整个回答打断） */
    private String deltaOf(String payload) {
        try {
            StreamChunk chunk = objectMapper.readValue(payload, StreamChunk.class);
            if (chunk == null || chunk.choices() == null || chunk.choices().isEmpty()) {
                return null;
            }
            Delta delta = chunk.choices().get(0).delta();
            return delta == null ? null : delta.content();
        } catch (Exception e) {
            log.debug("跳过一条解析不了的流式帧：{}", payload.length() > 120 ? payload.substring(0, 120) : payload);
            return null;
        }
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
    record ChatResponse(List<Choice> choices, Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(Integer prompt_tokens, Integer completion_tokens) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    /** 流式增量帧：{"choices":[{"delta":{"content":"每"}}]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamChunk(List<StreamChoice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamChoice(Delta delta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(String content) {
    }
}
