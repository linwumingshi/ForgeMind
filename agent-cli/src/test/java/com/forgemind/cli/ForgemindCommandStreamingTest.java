package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void streamingTaskShowsToolMarkersAndFinalAnswer() throws Exception {
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
        // P2.1 默认模式：中间 assistant 文本静默（不实时展示）
        assertFalse(text.contains("scanning the file"), "默认模式不应输出中间 assistant 文本: " + text);
        // Tool 生命周期：序号 + ✓
        assertTrue(text.contains("[1] read_file ✓"), "Tool 应显示序号与成功标记: " + text);
        // 最终答案块：完整 finalAnswer + 统计
        assertTrue(text.contains("-- Final answer --"));
        assertTrue(text.contains("read and done"), "最终答案应完整输出: " + text);
        assertTrue(text.indexOf("read and done") == text.lastIndexOf("read and done"),
                "最终答案只输出一次: " + text);
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
        assertTrue(text.contains("[1] read_file ✗"), "失败 Tool 应显示序号失败标记: " + text);
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
    void streamedFinalAnswerPrintedOnceComplete() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("unique-final-42"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--working-dir", tempDir.toString(), "--yes", "task");
        assertEquals(0, exit);
        String text = captured.text();
        // P2.1：不再输出 "(streamed above)" 占位；最终答案完整输出且只出现一次
        assertFalse(text.contains("(streamed above)"), "不应出现 (streamed above): " + text);
        assertTrue(text.contains("unique-final-42"), "最终答案应完整输出: " + text);
        assertTrue(text.indexOf("unique-final-42") == text.lastIndexOf("unique-final-42"),
                "final answer 只应输出一次: " + text);
    }

    // ---------- P2.4：--verbose ----------

    @Test
    void verboseShowsIntermediateTextAndToolOutput() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("scanning the file",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("read and done"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--verbose", "--working-dir", tempDir.toString(), "--yes", "read a.txt");
        assertEquals(0, exit);
        String text = captured.text();
        // verbose 显示 assistant 中间文本（默认模式静默）
        assertTrue(text.contains("scanning the file"), "verbose 应显示中间 assistant 文本: " + text);
        // verbose 缩进展示完整 tool output（read_file 返回文件内容）
        assertTrue(text.contains("    hello"), "verbose 应缩进展示 tool output: " + text);
        // 事件行仍按序号展示
        assertTrue(text.contains("[1] read_file ✓"), "事件行格式不变: " + text);
        // 最终答案完整输出一次
        assertTrue(text.contains("-- Final answer --"));
        assertTrue(text.contains("read and done"));
        assertTrue(text.indexOf("read and done") == text.lastIndexOf("read and done"),
                "final answer 只应输出一次: " + text);
        assertTrue(text.contains("iterations: 2  toolCalls: 1"));
    }

    @Test
    void verboseWithYesAndWorkingDirStillRuns() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("inspecting",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("done"));
        Captured captured = new Captured();
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--verbose", "--yes", "--working-dir", tempDir.toString(), "read");
        assertEquals(0, exit);
        String text = captured.text();
        assertTrue(text.contains("inspecting"), "verbose + --yes 组合应正常: " + text);
        assertTrue(text.contains("[1] read_file ✓"));
        assertTrue(text.contains("iterations: 2  toolCalls: 1"));
    }

    @Test
    void verboseWithConfigFlagParsesAndRuns() throws Exception {
        // --verbose 与 --config 可共存：config 由注入的 loader 提供，verbose 只影响展示
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("with config",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("config done"));
        Captured captured = new Captured();
        Path cfgPath = tempDir.resolve("p24.yml");
        int exit = new CommandLine(commandWith(fake, captured))
                .execute("--verbose", "--config", cfgPath.toString(),
                        "--working-dir", tempDir.toString(), "--yes", "read");
        assertEquals(0, exit);
        String text = captured.text();
        assertTrue(text.contains("with config"), "verbose + --config 组合应正常: " + text);
        assertTrue(text.contains("[1] read_file ✓"));
        assertTrue(text.contains("config done"));
    }
}
