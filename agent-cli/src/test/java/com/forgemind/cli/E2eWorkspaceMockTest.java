package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mock 端到端测试：真实 WorkspaceAccess + ToolExecutor + 6 个正式 AgentTool +
 * AgentLoop + FakeLlmClient 在独立沙箱中跑通 6 类任务，
 * 断言真实文件系统状态（而非仅 AgentResult）。
 */
class E2eWorkspaceMockTest {

    @TempDir
    Path workspace;

    private Agent agent(FakeLlmClient fake) {
        return CliAssembly.buildAgent(AgentConfig.defaults(), fake, workspace, req -> true);
    }

    @BeforeEach
    void seedWorkspace() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "hello forge mind", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Test.java"),
                "public class Test { }\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("doc.txt"), "alpha beta gamma\n", StandardCharsets.UTF_8);
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    @Test
    void readAndListFilesEndToEnd() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c1", "read_file", Map.of("path", "hello.txt")),
                        call("c2", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("done"));
        AgentResult result = agent(fake).run("read and list");
        assertTrue(result.finished());
        assertEquals(2, result.toolCallCount());
        List<ChatMessage> second = fake.calls().get(1);
        List<ChatMessage> toolMsgs = second.stream().filter(m -> m.role() == Role.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        assertTrue(toolMsgs.get(0).content().contains("hello forge mind"));
        assertTrue(toolMsgs.get(1).content().contains("hello.txt"));
        assertTrue(toolMsgs.get(1).content().contains("doc.txt"));
    }

    @Test
    void writeThenReadEndToEnd() throws IOException {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c1", "write_file",
                                Map.of("path", "out/new.txt", "content", "created by agent")))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c2", "read_file", Map.of("path", "out/new.txt")))))
                .then(AgentResponse.finalAnswer("written and read"));
        AgentResult result = agent(fake).run("create then read");
        assertTrue(result.finished());
        // 真实文件系统断言：文件已创建且内容正确
        assertTrue(Files.exists(workspace.resolve("out/new.txt")));
        assertEquals("created by agent",
                Files.readString(workspace.resolve("out/new.txt"), StandardCharsets.UTF_8));
        List<ChatMessage> third = fake.calls().get(2);
        assertTrue(third.stream().filter(m -> m.role() == Role.TOOL)
                .anyMatch(m -> m.content().contains("created by agent")),
                "read_file 的结果应回灌文件内容");
    }

    @Test
    void editThenSearchEndToEnd() throws IOException {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c1", "edit_file",
                                Map.of("path", "doc.txt", "oldText", "beta", "newText", "BETA")))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c2", "search", Map.of("query", "BETA")))))
                .then(AgentResponse.finalAnswer("edited and found"));
        AgentResult result = agent(fake).run("edit then search");
        assertTrue(result.finished());
        // 真实文件系统断言：编辑已生效
        assertEquals("alpha BETA gamma\n",
                Files.readString(workspace.resolve("doc.txt"), StandardCharsets.UTF_8));
        List<ChatMessage> third = fake.calls().get(2);
        assertTrue(third.stream().filter(m -> m.role() == Role.TOOL)
                .anyMatch(m -> m.content().contains("BETA") && m.content().contains("doc.txt")),
                "search 的结果应回灌匹配行与文件路径");
    }

    @Test
    void shellDirEndToEnd() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("c1", "shell", Map.of("command", "dir /b")))))
                .then(AgentResponse.finalAnswer("listed"));
        AgentResult result = agent(fake).run("list directory");
        assertTrue(result.finished());
        List<ChatMessage> second = fake.calls().get(1);
        ChatMessage shellMsg = second.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(shellMsg.content().contains("hello.txt"));
        assertTrue(shellMsg.content().contains("doc.txt"));
    }
}
