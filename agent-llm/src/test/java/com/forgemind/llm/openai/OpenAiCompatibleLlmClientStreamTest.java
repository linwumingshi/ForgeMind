package com.forgemind.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmStreamListener;
import com.forgemind.core.llm.LlmStreamResult;
import com.forgemind.core.retry.RetryPolicy;
import com.forgemind.core.retry.Sleeper;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ChatMessage;
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
 * OpenAiCompatibleLlmClient 流式测试：本地 HttpServer 模拟 SSE，不访问真实服务。
 */
class OpenAiCompatibleLlmClientStreamTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<>();
    private volatile int[] statusSequence;
    private volatile int statusIndex;
    private volatile int responseStatus = 200;
    private volatile List<String> sseChunks = List.of();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = statusSequence != null && statusIndex < statusSequence.length
                    ? statusSequence[statusIndex++] : responseStatus;
            if (code != 200) {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            for (String chunk : sseChunks) {
                os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.close();
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

    private OpenAiCompatibleLlmClient clientNoJitter(LlmConfig cfg) {
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(50), 2.0, false);
        return new OpenAiCompatibleLlmClient(cfg, List.of(readFileTool()), policy, Sleeper.NOOP);
    }

    private static AgentTool readFileTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "read a file";
            }

            @Override
            public ToolSchema schema() {
                return ToolSchema.of(Map.of("path", new ToolParameter("string", "p")), List.of("path"));
            }

            @Override
            public com.forgemind.core.permission.PermissionScope permissionScope() {
                return com.forgemind.core.permission.PermissionScope.READ;
            }

            @Override
            public ToolResult execute(com.forgemind.core.context.ToolContext context, Map<String, Object> arguments) {
                return ToolResult.success("ok");
            }
        };
    }

    private static String textChunk(String content, String finishReason) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":" + finish + "}]}";
    }

    private static String usageChunk(long prompt, long completion, long total) {
        return "{\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion + ",\"total_tokens\":" + total + "}}";
    }

    private static final class RecordingListener implements LlmStreamListener {
        final StringBuilder text = new StringBuilder();
        final List<String[]> toolDeltas = new ArrayList<>();
        LlmStreamResult result;
        LlmException error;

        @Override
        public void onTextDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onToolCallDelta(String id, String name, String arguments) {
            toolDeltas.add(new String[]{id, name, arguments});
        }

        @Override
        public void onComplete(LlmStreamResult result) {
            this.result = result;
        }

        @Override
        public void onError(LlmException error) {
            this.error = error;
        }
    }

    private RecordingListener run(List<String> chunks) {
        sseChunks = chunks;
        RecordingListener listener = new RecordingListener();
        clientNoJitter(config("k")).stream(List.of(ChatMessage.user("hi")), listener);
        return listener;
    }

    // ---------- 基础 ----------

    @Test
    void singleTextDelta() {
        RecordingListener l = run(List.of(textChunk("hello", "stop")));
        assertEquals("hello", l.text.toString());
        assertNotNull(l.result);
        assertEquals("hello", l.result.response().content());
        assertEquals("stop", l.result.response().finishReason());
        assertNull(l.error);
    }

    @Test
    void multipleTextDeltas() {
        RecordingListener l = run(List.of(textChunk("a", null), textChunk("b", null), textChunk("c", "stop")));
        assertEquals("abc", l.text.toString());
        assertEquals("abc", l.result.response().content());
    }

    @Test
    void chineseText() {
        RecordingListener l = run(List.of(textChunk("你", null), textChunk("好", "stop")));
        assertEquals("你好", l.text.toString());
    }

    @Test
    void usageFromFinalChunk() {
        RecordingListener l = run(List.of(textChunk("done", "stop"), usageChunk(10, 5, 15)));
        assertTrue(l.result.hasUsage());
        assertEquals(10, l.result.promptTokens());
        assertEquals(5, l.result.completionTokens());
        assertEquals(15, l.result.totalTokens());
    }

    @Test
    void noUsageNotFabricated() {
        RecordingListener l = run(List.of(textChunk("done", "stop")));
        assertFalse(l.result.hasUsage());
        assertEquals(0, l.result.totalTokens());
    }

    // ---------- Tool ----------

    @Test
    void singleToolCall() {
        String chunk = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        RecordingListener l = run(List.of(chunk));
        assertTrue(l.result.response().hasToolCalls());
        assertEquals("c0", l.result.response().toolCalls().get(0).id());
        assertEquals("read_file", l.result.response().toolCalls().get(0).name());
        assertEquals(Map.of("path", "a.txt"), l.result.response().toolCalls().get(0).arguments());
        assertEquals("tool_calls", l.result.response().finishReason());
    }

    @Test
    void toolArgumentsSplitAcrossChunks() {
        String c1 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]},\"finish_reason\":null}]}";
        String c2 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"th\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        RecordingListener l = run(List.of(c1, c2));
        assertTrue(l.result.response().hasToolCalls());
        assertEquals(Map.of("path", "a.txt"), l.result.response().toolCalls().get(0).arguments());
    }

    @Test
    void multipleToolCalls() {
        String chunk = "{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"c0\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"id\":\"c1\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        RecordingListener l = run(List.of(chunk));
        assertEquals(2, l.result.response().toolCalls().size());
        assertEquals("c0", l.result.response().toolCalls().get(0).id());
        assertEquals("c1", l.result.response().toolCalls().get(1).id());
    }

    @Test
    void textAndToolCallMixed() {
        RecordingListener l = run(List.of(
                textChunk("我先读取文件", null),
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}"));
        assertEquals("我先读取文件", l.result.response().content());
        assertTrue(l.result.response().hasToolCalls());
    }

    // ---------- 异常 ----------

    @Test
    void malformedChunksAreIgnored() {
        RecordingListener l = run(List.of("{not-json", textChunk("ok", "stop")));
        assertEquals("ok", l.text.toString());
        assertNotNull(l.result);
        assertNull(l.error);
    }

    @Test
    void httpClientErrorsAreNotRetried() {
        for (int status : new int[]{400, 401, 403, 404, 422}) {
            statusSequence = new int[]{status};
            statusIndex = 0;
            requestBodies.clear();
            RecordingListener l = new RecordingListener();
            clientNoJitter(config("k")).stream(List.of(ChatMessage.user("q")), l);
            assertNotNull(l.error, "HTTP " + status + " 应立即失败");
            assertTrue(l.error.getMessage().contains(String.valueOf(status)));
            assertEquals(1, requestBodies.size());
            assertNull(l.result);
        }
    }

    // ---------- Retry ----------

    @Test
    void retryStatusThenSuccess() {
        for (int status : new int[]{429, 500, 502, 503, 504}) {
            statusSequence = new int[]{status, 200};
            statusIndex = 0;
            requestBodies.clear();
            sseChunks = List.of(textChunk("ok", "stop"));
            RecordingListener l = new RecordingListener();
            clientNoJitter(config("k")).stream(List.of(ChatMessage.user("q")), l);
            assertNull(l.error, "HTTP " + status + " 应重试后成功");
            assertEquals("ok", l.result.response().content());
            assertEquals(2, requestBodies.size(), "HTTP " + status + " 应重试一次");
        }
    }

    @Test
    void retriesExhausted() {
        statusSequence = new int[]{500, 500, 500};
        RecordingListener l = new RecordingListener();
        clientNoJitter(config("k")).stream(List.of(ChatMessage.user("q")), l);
        assertNotNull(l.error);
        assertTrue(l.error.getMessage().contains("500"));
        assertEquals(3, requestBodies.size());
        assertNull(l.result);
    }

    @Test
    void noRetryAfterBodyStarted() {
        // 服务器 200 后发一个 chunk 就关闭连接：客户端已收到部分 delta，禁止重试
        sseChunks = List.of(textChunk("你好", null)); // handler 写完即 close（EOF）
        RecordingListener l = new RecordingListener();
        clientNoJitter(config("k")).stream(List.of(ChatMessage.user("q")), l);
        assertEquals("你好", l.text.toString(), "已产生的 delta 必须保留");
        assertEquals(1, requestBodies.size(), "body 已开始后绝不允许重试");
        // EOF 正常结束（onComplete）或中断（onError）至少其一发生；均不得重发请求
        assertTrue(l.result != null || l.error != null);
    }

    @Test
    void apiKeyNotLeakedInErrors() {
        statusSequence = new int[]{500, 500, 500};
        RecordingListener l = new RecordingListener();
        clientNoJitter(config("secret-key-123")).stream(List.of(ChatMessage.user("q")), l);
        assertNotNull(l.error);
        assertFalse(l.error.getMessage().contains("secret-key-123"));
    }
}
