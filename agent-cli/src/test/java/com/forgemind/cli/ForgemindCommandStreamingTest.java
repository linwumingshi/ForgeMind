package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.cli.config.ConfigLoader;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ToolCall;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * M8.5：CLI 端到端 —— Streaming 增量输出与 Tool 展示经 ForgemindCommand
 * 真实接线（StreamingProgressRenderer 注入 AgentLoop）。
 */
class ForgemindCommandStreamingTest {

    @TempDir
    Path tempDir;

    private static final class Captured {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private ForgemindCommand commandWith(FakeLlmClient fake, Captured captured) {
        return new ForgemindCommand(cfg -> fake, file -> new ConfigLoader.Loaded(
                AgentConfig.defaults(),
                new LlmConfig("http://x/v1", "k", "m",
                        Duration.ofSeconds(5), Duration.ofSeconds(5))),
                captured.out, new ByteArrayInputStream(new byte[0]));
    }

    @Test
    void streamingTaskPrintsDeltasAndToolMarkers() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("scanning the file",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("read and done"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "read a.txt");
        assertEquals(0, exit);
        String text = captured.text();
        // 文本增量实时输出（完整拼接 = 两轮 content）
        assertTrue(text.contains("scanning the file"), "第一轮 text delta 应被渲染");
        assertTrue(text.contains("read and done"), "第二轮 text delta 应被渲染");
        // Tool 调用/结果展示
        assertTrue(text.contains("[tool: read_file] [success]"), "Tool 生命周期应被渲染");
        // 最终答案块仍由 ForgemindApp 统一输出
        assertTrue(text.contains("-- Final answer --"));
        assertTrue(text.contains("iterations: 2  toolCalls: 1"));
    }

    @Test
    void toolFailureShowsFailedMarker() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("attempt",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "missing.txt")))))
                .then(AgentResponse.finalAnswer("self corrected"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "read missing");
        assertEquals(0, exit);
        String text = captured.text();
        assertTrue(text.contains("[tool: read_file] [failed]"), "失败 Tool 应显示 [failed]");
        assertTrue(text.contains("self corrected"));
    }

    // ---------- M9.4 ----------

    @Test
    void subAgentLifecycleShownWithStatusSummary() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("delegating",
                        List.of(ToolCall.of("s1", "sub_agent", Map.of(
                                "task", "count files",
                                "tools", List.of("list_files"))))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("l1", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("sub done"))
                .then(AgentResponse.finalAnswer("main done"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "delegate");
        assertEquals(0, exit);
        String text = captured.text();
        // 实时展示 SubAgent 生命周期
        assertTrue(text.contains("[subagent:start] count files [complete]"),
                "SubAgent 生命周期应实时展示: " + text);
        // 状态摘要：success + subAgents 计数
        assertTrue(text.contains("status: success"));
        assertTrue(text.contains("subAgents: 1"));
        assertTrue(text.contains("iterations: 2  toolCalls: 1"));
    }

    @Test
    void subAgentFailureShownInLifecycleAndSummary() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("delegating",
                        List.of(ToolCall.of("s1", "sub_agent", Map.of(
                                "task", "limited",
                                "tools", List.of("list_files"),
                                "maxIterations", 1)))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("l1", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("main recovered"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "delegate");
        assertEquals(0, exit);
        String text = captured.text();
        assertTrue(text.contains("[subagent:start] limited [failed]"),
                "失败的 SubAgent 应显示 [failed]: " + text);
        assertTrue(text.contains("status: success"), "主任务自纠后应 success");
        assertTrue(text.contains("subAgents: 1"));
    }

    @Test
    void streamedFinalAnswerNotDuplicated() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("unique-final-42"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "task");
        assertEquals(0, exit);
        String text = captured.text();
        // streaming 已输出 delta：Final block 用占位，不重复文本
        assertTrue(text.contains("(streamed above)"));
        assertTrue(text.indexOf("unique-final-42") == text.lastIndexOf("unique-final-42"),
                "final answer 只应出现一次（delta），不得重复: " + text);
    }
}
