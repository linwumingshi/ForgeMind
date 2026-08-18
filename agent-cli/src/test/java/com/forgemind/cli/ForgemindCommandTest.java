package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ForgemindCommandTest {

    @TempDir
    Path tempDir;

    /** 注入 Fake LLM 与固定配置加载器；输出进入匿名流（通过副作用/调用记录断言）。 */
    private ForgemindCommand commandWith(FakeLlmClient fake) {
        return new ForgemindCommand(cfg -> fake, file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                AgentConfig.defaults(),
                new LlmConfig("http://x/v1", "k", "m", Duration.ofSeconds(5), Duration.ofSeconds(5))),
                new PrintStream(new ByteArrayOutputStream()), new ByteArrayInputStream(new byte[0]));
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    @Test
    void helpOptionPrintsUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmdLine = new CommandLine(new ForgemindCommand(cfg -> new FakeLlmClient(),
                file -> null, new PrintStream(out), new ByteArrayInputStream(new byte[0])));
        cmdLine.setOut(new PrintWriter(out, true));
        int exit = cmdLine.execute("--help");
        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void runsSingleTaskModeWithWorkingDir() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "hello");
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("task done"));
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "--yes", "read the file");
        assertEquals(0, exit);
        // 两轮调用：工具调用 + 最终答案
        assertEquals(2, fake.callCount());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("hello"), "工具应在指定工作目录执行");
    }

    @Test
    void yesFlagAllowsWritePermission() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "write_file", Map.of("path", "created.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("written"));
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "--yes", "create a file");
        assertEquals(0, exit);
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("created.txt")),
                "--yes 应自动允许 WRITE 权限询问");
    }

    @Test
    void withoutYesWriteIsDeniedByDefault() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "write_file", Map.of("path", "denied.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("done"));
        // 无 --yes：InteractivePermissionAnswerer 读到 EOF → 视为 N（拒绝）
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "create a file");
        assertEquals(0, exit);
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("denied.txt")),
                "默认权限策略应拒绝写入");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"));
    }

    @Test
    void defaultWorkingDirIsCurrentDirectory() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        int exit = new CommandLine(commandWith(fake)).execute("--yes", "task");
        assertEquals(0, exit);
        assertEquals(1, fake.callCount());
    }
}
