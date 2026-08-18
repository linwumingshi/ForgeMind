package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Streaming Tool Calling 闭环：stream → tool_call → ToolExecutor → ToolResult →
 * 第二轮 stream → final。工具必须等完整 tool_call 组装后经 ToolExecutor 执行。
 */
class AgentLoopStreamToolFlowTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
    }

    private AgentLoop buildAgent(FakeLlmClient fake) {
        return com.forgemind.cli.AgentHarness.newLoop(workspace, fake,
                AgentConfig.defaults(), req -> true, ProgressListener.NOOP);
    }

    @Test
    void streamToolThenStreamFinal() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("read and done"));
        AgentResult result = buildAgent(fake).run("read a.txt");
        assertTrue(result.finished());
        assertEquals("read and done", result.finalAnswer());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolCallCount());
    }

    @Test
    void streamMultipleToolsThenFinal() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        ToolCall.of("c1", "read_file", Map.of("path", "a.txt")),
                        ToolCall.of("c2", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("both tools done"));
        AgentResult result = buildAgent(fake).run("read and list");
        assertTrue(result.finished());
        assertEquals(2, result.toolCallCount());
        assertEquals(2, result.iterations());
    }

    @Test
    void streamingToolFailureSelfCorrects() {
        // 第一轮工具越界失败 → 回灌 → 第二轮 stream 自纠 → final
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "../outside.txt")))))
                .then(AgentResponse.finalAnswer("self corrected"));
        AgentResult result = buildAgent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("self corrected", result.finalAnswer());
        assertEquals(2, result.iterations());
    }
}
