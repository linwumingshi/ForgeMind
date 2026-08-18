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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8.4：ProgressListener 观察面。text delta / tool 开始 / tool 结果均被回调；
 * NOOP 安全；监听器抛异常不得影响核心 AgentLoop 任务。
 */
class ProgressListenerTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
    }

    private AgentLoop buildAgent(FakeLlmClient fake, ProgressListener progress) {
        return com.forgemind.cli.AgentHarness.newLoop(workspace, fake,
                AgentConfig.defaults(), req -> true, progress);
    }

    private FakeLlmClient toolThenFinalScript() {
        return new FakeLlmClient()
                .then(AgentResponse.withToolCalls("inspecting",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("done"));
    }

    @Test
    void receivesDeltasAndToolLifecycle() {
        List<String> deltas = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> finishedTools = new ArrayList<>();
        List<Boolean> finishedSuccess = new ArrayList<>();
        ProgressListener progress = new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onToolCallStarted(String toolName) {
                started.add(toolName);
            }

            @Override
            public void onToolResult(String toolName, boolean success) {
                finishedTools.add(toolName);
                finishedSuccess.add(success);
            }
        };

        AgentResult result = buildAgent(toolThenFinalScript(), progress).run("task");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());

        // 两轮 text delta 顺序拼接 = 两轮完整 content
        assertEquals("inspectingdone", String.join("", deltas));
        // tool 生命周期回调
        assertEquals(List.of("read_file"), started);
        assertEquals(List.of("read_file"), finishedTools);
        assertEquals(List.of(true), finishedSuccess);
    }

    @Test
    void noopListenerIsSafe() {
        AgentResult result = buildAgent(toolThenFinalScript(), ProgressListener.NOOP).run("task");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
    }

    @Test
    void listenerExceptionsAreIgnored() {
        // onTextDelta / onToolCallStarted / onToolResult 全部抛异常 → 任务照常完成
        AgentResult result = buildAgent(toolThenFinalScript(), new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
                throw new IllegalStateException("listener boom: delta");
            }

            @Override
            public void onToolCallStarted(String toolName) {
                throw new IllegalStateException("listener boom: started");
            }

            @Override
            public void onToolResult(String toolName, boolean success) {
                throw new IllegalStateException("listener boom: result");
            }
        }).run("task");
        assertTrue(result.finished(), "监听器异常不得影响 AgentLoop");
        assertEquals("done", result.finalAnswer());
    }
}
