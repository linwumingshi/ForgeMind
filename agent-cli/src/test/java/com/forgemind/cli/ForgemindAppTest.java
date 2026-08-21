package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgemindAppTest {

    @TempDir
    Path tempDir;

    private ForgemindApp appWithInput(String input) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner);
    }

    private com.forgemind.core.Agent fakeAgent() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("fake answer"));
        return CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, (PermissionAnswerer) req -> false);
    }

    @Test
    void singleTaskModeRunsOnceAndPrintsStats() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8);
        new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner)
                .run(fakeAgent(), "do something", tempDir);
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("fake answer"));
        assertTrue(out.contains("iterations: 1  toolCalls: 0"));
    }

    @Test
    void replRunsMultipleTasksUntilExit() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(
                "first task\nexit\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner)
                .run(fakeAgent(), null, tempDir);
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Working directory:"));
        assertTrue(out.contains("fake answer"), "REPL 中的任务应执行并输出结果");
    }

    @Test
    void exitImmediately() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(
                "exit\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner)
                .run(fakeAgent(), null, tempDir);
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Type 'exit' to quit."));
    }

    @Test
    void blankLinesAreIgnoredInRepl() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(
                "\n\n  \nexit\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner)
                .run(fakeAgent(), null, tempDir);
        assertEquals(1, 1); // 空行不触发任务，正常退出
    }

    // ---------- P2.4：REPL 多轮 verbose 状态 ----------

    @Test
    void replVerboseStatePersistsAcrossTurns() {
        // verbose 是 CLI session 级别：setVerbose(true) 后 REPL 每轮都显示中间文本与 tool output；
        // reset() 只归零 tool 序号，不清除 verbose。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream(
                "task one\ntask two\nexit\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        StreamingProgressRenderer renderer = new StreamingProgressRenderer(
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        renderer.setVerbose(true);
        // FakeLlmClient 是 LlmStreamClient：最终答案经 delta 事件 → verbose 下实时输出
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("answer one"))
                .then(AgentResponse.finalAnswer("answer two"));
        com.forgemind.core.Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake,
                tempDir, (PermissionAnswerer) req -> false, renderer);
        new ForgemindApp(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner, renderer)
                .run(agent, null, tempDir);
        String out = buffer.toString(StandardCharsets.UTF_8);
        // 两轮 finalAnswer 均完整输出（REPL 两轮都执行成功）
        assertTrue(out.contains("answer one"), "第一轮 finalAnswer 应输出: " + out);
        assertTrue(out.contains("answer two"), "第二轮 finalAnswer 应输出: " + out);
    }
}
