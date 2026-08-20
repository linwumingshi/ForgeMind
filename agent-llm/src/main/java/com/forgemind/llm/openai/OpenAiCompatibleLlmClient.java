package com.forgemind.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.llm.LlmStreamClient;
import com.forgemind.core.llm.LlmStreamListener;
import com.forgemind.core.llm.LlmStreamResult;
import com.forgemind.core.retry.RetryPolicy;
import com.forgemind.core.retry.Sleeper;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolSchema;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-Compatible Chat Completions 客户端（OpenAI / DeepSeek / 其他兼容服务）。
 *
 * <p>实现层使用 JDK {@link HttpClient}（零第三方 HTTP 依赖），JSON 编解码使用
 * Jackson（经 agent-model 传递引入）。支持阻塞 {@link #chat} 与 SSE 流式
 * {@link #stream}（M8）。</p>
 *
 * <p>工具定义经构造参数注入（{@link AgentTool#schema()} → OpenAI function tool），
 * {@link LlmClient#chat} SPI 签名不变，不被具体 Provider 反向污染。</p>
 *
 * <p>安全：Authorization 使用 Bearer；API Key 绝不进入日志、异常信息或输出。
 * 非 2xx / 超时 / IO / 中断 / choices 为空 → {@link LlmException}；
 * {@code tool_calls.arguments} JSON 解析失败 → 回传空参数，交由
 * AgentLoop/ToolExecutor 参数校验回灌自纠。</p>
 */
public final class OpenAiCompatibleLlmClient implements LlmClient, LlmStreamClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final LlmConfig config;
    private final List<AgentTool> tools;
    private final HttpClient httpClient;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleLlmClient(LlmConfig config, List<AgentTool> tools) {
        this(config, tools, new RetryPolicy(), Sleeper.REAL);
    }

    public OpenAiCompatibleLlmClient(LlmConfig config, List<AgentTool> tools,
                                     RetryPolicy retryPolicy, Sleeper sleeper) {
        this.config = Objects.requireNonNull(config, "config");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public AgentResponse chat(List<ChatMessage> messages) {
        String body = buildRequestBody(messages);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(config.readTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        int attempt = 0;
        while (true) {
            HttpResponse<String> response = send(request);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parseResponse(response.body());
            }
            // 仅可重试状态重试（指数退避）；否则立即失败
            if (!retryPolicy.isRetryable(status) || attempt >= retryPolicy.maxRetries()) {
                throw new LlmException("LLM API error: HTTP " + status + " - " + extractError(response.body()));
            }
            attempt++;
            log.debug("LLM API retry {}/{} for HTTP {}", attempt, retryPolicy.maxRetries(), status);
            sleeper.sleep(retryPolicy.backoffFor(attempt));
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("LLM request failed (IO): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted", e);
        }
    }

    /**
     * 流式调用（M8）：SSE 增量经 listener 回调，完成时携带完整 AgentResponse 与 usage。
     *
     * <p>Retry 语义：仅"响应 body 开始消费前"可重试（连接建立失败、HTTP
     * 429/500/502/503/504）；一旦 2xx 且 SSE 已开始读取，绝不重试（避免重复输出）。</p>
     */
    @Override
    public void stream(List<ChatMessage> messages, LlmStreamListener listener) {
        Objects.requireNonNull(listener, "listener");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(config.readTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(buildStreamRequestBody(messages)))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = sendWithStreamRetry(request);
        } catch (LlmException e) {
            listener.onError(e);
            return;
        }

        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator();
        OpenAiSseParser.parse(response.body(), new OpenAiSseParser.Listener() {
            @Override
            public void onData(String data) {
                accumulator.accept(data, deltaObserver(listener));
            }

            @Override
            public void onComplete() {
                AgentResponse agentResponse = accumulator.finish();
                listener.onComplete(accumulator.hasUsage()
                        ? LlmStreamResult.of(agentResponse,
                                accumulator.promptTokens(), accumulator.completionTokens(),
                                accumulator.totalTokens())
                        : LlmStreamResult.of(agentResponse));
            }

            @Override
            public void onError(IOException error) {
                listener.onError(new LlmException(
                        "LLM stream interrupted: " + error.getMessage(), error));
            }
        });
    }

    /** 流式增量 → core listener（丢弃 OpenAI 专有 index）。 */
    private static OpenAiStreamAccumulator.DeltaObserver deltaObserver(LlmStreamListener listener) {
        return new OpenAiStreamAccumulator.DeltaObserver() {
            @Override
            public void onTextDelta(String text) {
                listener.onTextDelta(text);
            }

            @Override
            public void onToolCallDelta(int index, String id, String name, String arguments) {
                listener.onToolCallDelta(id, name, arguments);
            }
        };
    }

    /**
     * 发送流式请求并执行"响应头阶段"重试：连接建立失败 / 429 / 500 / 502 / 503 / 504。
     * 2xx 后不再重试（body 消费阶段由调用方处理）。
     */
    private HttpResponse<InputStream> sendWithStreamRetry(HttpRequest request) {
        int attempt = 0;
        while (true) {
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                if (attempt < retryPolicy.maxRetries()) {
                    attempt++;
                    log.debug("LLM stream retry {}/{} after IO failure", attempt, retryPolicy.maxRetries());
                    sleeper.sleep(retryPolicy.backoffFor(attempt));
                    continue;
                }
                throw new LlmException("LLM stream request failed (IO): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("LLM stream request interrupted", e);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response;
            }
            String errorBody = readErrorBody(response.body());
            if (!retryPolicy.isRetryable(status) || attempt >= retryPolicy.maxRetries()) {
                throw new LlmException("LLM API error: HTTP " + status + " - " + extractError(errorBody));
            }
            attempt++;
            log.debug("LLM stream retry {}/{} for HTTP {}", attempt, retryPolicy.maxRetries(), status);
            sleeper.sleep(retryPolicy.backoffFor(attempt));
        }
    }

    /** 非 2xx 错误体（有限读取，避免大 body）。 */
    private static String readErrorBody(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            return new String(in.readNBytes(1000), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略关闭失败
            }
        }
    }

    /** 流式请求体：与 chat 一致 + {@code stream: true}。 */
    private String buildStreamRequestBody(List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages.stream().map(this::toWireMessage).toList());
        body.put("tools", tools.stream().map(this::toWireTool).toList());
        body.put("tool_choice", "auto");
        body.put("stream", true);
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("failed to serialize LLM request: " + e.getMessage(), e);
        }
    }

    private String endpoint() {
        String base = config.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    // ---------- 请求构建 ----------

    private String buildRequestBody(List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages.stream().map(this::toWireMessage).toList());
        body.put("tools", tools.stream().map(this::toWireTool).toList());
        body.put("tool_choice", "auto");
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("failed to serialize LLM request: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toWireMessage(ChatMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role().name().toLowerCase(Locale.ROOT));
        switch (message.role()) {
            case SYSTEM, USER -> wire.put("content", message.content());
            case ASSISTANT -> {
                wire.put("content", message.content() == null ? null : message.content());
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    wire.put("tool_calls", message.toolCalls().stream().map(this::toWireToolCall).toList());
                }
            }
            case TOOL -> {
                wire.put("tool_call_id", message.toolCallId());
                wire.put("content", message.content());
            }
        }
        return wire;
    }

    private Map<String, Object> toWireToolCall(ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", toArgumentsJson(call.arguments()));
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", call.id());
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
    }

    private String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException e) {
            throw new LlmException("failed to serialize tool call arguments: " + e.getMessage(), e);
        }
    }

    /** AgentTool.schema() → OpenAI function tool 描述。 */
    private Map<String, Object> toWireTool(AgentTool tool) {
        ToolSchema schema = tool.schema();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", ToolSchema.TYPE_OBJECT);
        parameters.put("properties", schema.properties().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toWireParameter(e.getValue()),
                        (a, b) -> a, LinkedHashMap::new)));
        if (schema.required() != null && !schema.required().isEmpty()) {
            parameters.put("required", schema.required());
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", parameters);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
    }

    private static Map<String, Object> toWireParameter(ToolParameter parameter) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", parameter.type());
        if (parameter.description() != null && !parameter.description().isEmpty()) {
            wire.put("description", parameter.description());
        }
        return wire;
    }

    // ---------- 响应解析 ----------

    private AgentResponse parseResponse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("invalid JSON in LLM response", e);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("LLM response contains no choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode finishReasonNode = choice.get("finish_reason");
        String finishReason = finishReasonNode == null || finishReasonNode.isNull()
                ? null : finishReasonNode.asText();
        log.debug("finish_reason={}", finishReason);

        JsonNode message = choice.get("message");
        if (message == null || message.isNull()) {
            throw new LlmException("LLM response choice has no message");
        }
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;

        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && !toolCallsNode.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode callNode : toolCallsNode) {
                String id = textOrEmpty(callNode.get("id"));
                JsonNode function = callNode.get("function");
                String name = function == null ? "" : textOrEmpty(function.get("name"));
                String argumentsJson = function == null || function.get("arguments") == null
                        ? "" : function.get("arguments").asText();
                calls.add(ToolCall.of(id, name, parseArguments(argumentsJson, name)));
            }
            return AgentResponse.withFinishReason(content, calls, finishReason);
        }
        return AgentResponse.withFinishReason(content, null, finishReason);
    }

    /** arguments 是 JSON 字符串；解析失败 → 空 Map（参数校验回灌 missing required → LLM 自纠）。 */
    private Map<String, Object> parseArguments(String argumentsJson, String toolName) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("tool '{}' returned invalid arguments JSON, falling back to empty args: {}",
                    toolName, e.getMessage());
            return Map.of();
        }
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    /** 从错误响应体提取 message（不包含 API Key；超长截断）。 */
    private static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "empty error body";
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode error = root.get("error");
            JsonNode message = error == null ? null : error.get("message");
            if (message != null && !message.isNull()) {
                return message.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 错误体，走截断逻辑
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
