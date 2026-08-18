package com.forgemind.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OpenAiCompatibleLlmClient 测试：使用 JDK 内置 HttpServer 本地 mock
 * /chat/completions，不访问任何真实服务。
 */
class OpenAiCompatibleLlmClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + ((InetSocketAddress) server.getAddress()).getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmConfig config(String apiKey) {
        return new LlmConfig(baseUrl + "/v1", apiKey, "test-model",
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private OpenAiCompatibleLlmClient client(LlmConfig config, AgentTool... tools) {
        return new OpenAiCompatibleLlmClient(config, List.of(tools));
    }

    private static final class FakeTool implements AgentTool {
        private final String name;
        private final ToolSchema schema;

        FakeTool(String name, ToolSchema schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Description of " + name;
        }

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public PermissionScope permissionScope() {
            return PermissionScope.READ;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
            return ToolResult.success("ok");
        }
    }

    private static FakeTool readFileTool() {
        return new FakeTool("read_file",
                ToolSchema.of(Map.of("path", new ToolParameter("string", "file path")), List.of("path")));
    }

    private static String finalResponse(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"},\"finish_reason\":\"stop\"}]}";
    }

    // ---------- 请求构建 ----------

    @Test
    void buildsRequestWithModelMessagesToolsAndToolChoice() throws Exception {
        responseBody = finalResponse("ok");
        client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("hello")));
        JsonNode body = new ObjectMapper().readTree(requestBodies.get(0));
        assertEquals("test-model", body.get("model").asText());
        assertEquals("auto", body.get("tool_choice").asText());
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertEquals("hello", body.get("messages").get(0).get("content").asText());
        assertTrue(body.has("tools"));
    }

    @Test
    void authorizationHeaderUsesBearer() {
        responseBody = finalResponse("ok");
        client(config("secret-key-123"), readFileTool()).chat(List.of(ChatMessage.user("hi")));
        assertEquals("Bearer secret-key-123", authHeaders.get(0));
    }

    @Test
    void toolsSchemaIsSerializedAsFunctionTools() throws Exception {
        responseBody = finalResponse("ok");
        client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("hi")));
        JsonNode body = new ObjectMapper().readTree(requestBodies.get(0));
        JsonNode tool = body.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("read_file", tool.get("function").get("name").asText());
        assertEquals("object", tool.get("function").get("parameters").get("type").asText());
        assertEquals("path", tool.get("function").get("parameters").get("required").get(0).asText());
    }

    @Test
    void toolMessageIsMappedToWire() throws Exception {
        responseBody = finalResponse("ok");
        client(config("k"), readFileTool())
                .chat(List.of(ChatMessage.tool("call-7", "some content")));
        JsonNode message = new ObjectMapper().readTree(requestBodies.get(0)).get("messages").get(0);
        assertEquals("tool", message.get("role").asText());
        assertEquals("call-7", message.get("tool_call_id").asText());
        assertEquals("some content", message.get("content").asText());
    }

    @Test
    void assistantToolCallsAreMappedToWire() throws Exception {
        responseBody = finalResponse("ok");
        client(config("k"), readFileTool()).chat(List.of(
                ChatMessage.assistantToolCalls(List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt"))))));
        JsonNode message = new ObjectMapper().readTree(requestBodies.get(0)).get("messages").get(0);
        assertEquals("assistant", message.get("role").asText());
        JsonNode call = message.get("tool_calls").get(0);
        assertEquals("c1", call.get("id").asText());
        assertEquals("function", call.get("type").asText());
        assertEquals("read_file", call.get("function").get("name").asText());
        assertEquals("{\"path\":\"a.txt\"}", call.get("function").get("arguments").asText());
    }

    // ---------- 响应解析 ----------

    @Test
    void parsesFinalContent() {
        responseBody = finalResponse("42");
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertFalse(response.hasToolCalls());
        assertEquals("42", response.content());
    }

    @Test
    void parsesToolCalls() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertTrue(response.hasToolCalls());
        ToolCall call = response.toolCalls().get(0);
        assertEquals("call-1", call.id());
        assertEquals("read_file", call.name());
        assertEquals(Map.of("path", "a.txt"), call.arguments());
    }

    @Test
    void invalidArgumentsJsonFallsBackToEmptyMap() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"not-json\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertTrue(response.hasToolCalls());
        assertTrue(response.toolCalls().get(0).arguments().isEmpty());
    }

    @Test
    void missingToolNameOrIdBecomesEmptyString() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        ToolCall call = response.toolCalls().get(0);
        assertEquals("", call.id());
        assertEquals("", call.name());
    }

    // ---------- 错误处理 ----------

    @Test
    void http401ThrowsLlmException() {
        responseStatus = 401;
        responseBody = "{\"error\":{\"message\":\"Invalid API key provided\"}}";
        LlmException e = assertThrows(LlmException.class,
                () -> client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q"))));
        assertTrue(e.getMessage().contains("401"));
        assertTrue(e.getMessage().contains("Invalid API key"));
    }

    @Test
    void http429ThrowsLlmException() {
        responseStatus = 429;
        responseBody = "{\"error\":{\"message\":\"rate limited\"}}";
        assertThrows(LlmException.class,
                () -> client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q"))));
    }

    @Test
    void http500ThrowsLlmException() {
        responseStatus = 500;
        assertThrows(LlmException.class,
                () -> client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q"))));
    }

    @Test
    void readTimeoutThrowsLlmException() {
        delayMillis = 3000;
        LlmConfig slow = new LlmConfig(baseUrl + "/v1", "k", "m",
                Duration.ofSeconds(5), Duration.ofMillis(400));
        assertThrows(LlmException.class,
                () -> client(slow, readFileTool()).chat(List.of(ChatMessage.user("q"))));
    }

    @Test
    void emptyChoicesThrowsLlmException() {
        responseBody = "{\"choices\":[]}";
        assertThrows(LlmException.class,
                () -> client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q"))));
    }

    @Test
    void missingMessageThrowsLlmException() {
        responseBody = "{\"choices\":[{}]}";
        assertThrows(LlmException.class,
                () -> client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q"))));
    }

    @Test
    void contentNullWithoutToolCalls() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null},"
                + "\"finish_reason\":\"stop\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertFalse(response.hasToolCalls());
        assertNull(response.content());
    }

    @Test
    void finishReasonStopIsParsed() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertEquals("stop", response.finishReason());
    }

    @Test
    void finishReasonToolCallsIsParsed() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertEquals("tool_calls", response.finishReason());
        assertTrue(response.hasToolCalls());
    }

    @Test
    void finishReasonLengthIsParsed() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},"
                + "\"finish_reason\":\"length\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertEquals("length", response.finishReason());
        assertEquals("partial", response.content());
    }

    @Test
    void finishReasonLengthWithNullContent() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null},"
                + "\"finish_reason\":\"length\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertEquals("length", response.finishReason());
        assertNull(response.content());
        assertFalse(response.hasToolCalls());
    }

    @Test
    void unknownFinishReasonIsKept() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},"
                + "\"finish_reason\":\"weird_value\"}]}";
        AgentResponse response = client(config("k"), readFileTool()).chat(List.of(ChatMessage.user("q")));
        assertEquals("weird_value", response.finishReason());
    }

    @Test
    void providerNameIsOpenAiCompatible() {
        assertEquals("openai-compatible", client(config("k"), readFileTool()).provider());
    }
}
