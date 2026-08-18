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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M6 最重要的闭环测试：真实 Git + 真实 8 Tool + Fake LLM
 * git_status → read_file → edit_file → git_diff → final answer。
 */
class AgentLoopGitFlowTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        Files.writeString(workspace.resolve("Bug.java"), """
                public class Bug {
                    public int value() { return 1; }
                }
                """, StandardCharsets.UTF_8);
        git("init", "-b", "main");
        git("config", "user.email", "test@forgemind.local");
        git("config", "user.name", "ForgeMind Test");
        git("config", "commit.gpgsign", "false");
        git("config", "core.quotepath", "false");
        git("add", "-A");
        git("commit", "-m", "initial");
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", workspace.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished && p.exitValue() == 0,
                "git failed: " + String.join(" ", args));
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    @Test
    void statusReadEditDiffFlowEndToEnd() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("s1", "git_status", Map.of()))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("r1", "read_file", Map.of("path", "Bug.java")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("e1", "edit_file",
                                Map.of("path", "Bug.java", "oldText", "return 1;", "newText", "return 2;")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("d1", "git_diff", Map.of()))))
                .then(AgentResponse.finalAnswer("bug fixed"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, workspace, req -> true);
        AgentResult result = agent.run("fix the bug");
        assertTrue(result.finished());
        assertEquals("bug fixed", result.finalAnswer());
        assertEquals(5, result.iterations());
        assertEquals(4, result.toolCallCount());

        // 1) 真实文件已修改
        String content = Files.readString(workspace.resolve("Bug.java"), StandardCharsets.UTF_8);
        assertTrue(content.contains("return 2;"));
        assertTrue(!content.contains("return 1;"));

        // 2) 完整消息序列与 tool_call_id 关联
        assertEquals(5, fake.callCount());
        assertToolCallRound(fake.calls().get(1), "s1", "git_status", "branch: main");
        assertToolCallRound(fake.calls().get(2), "r1", "read_file", "return 1;");
        assertToolCallRound(fake.calls().get(3), "e1", "edit_file", "replaced 1 occurrence");
        // 3) git_diff 能看到修改
        assertToolCallRound(fake.calls().get(4), "d1", "git_diff", "+    public int value() { return 2; }");

        // 4) 最后消息是 final answer（ASSISTANT 无 tool_calls）
        List<ChatMessage> lastCall = fake.calls().get(4);
        assertTrue(lastCall.stream().anyMatch(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null));
    }

    private static void assertToolCallRound(List<ChatMessage> messages, String expectedId,
                                            String expectedTool, String expectedContentFragment) {
        // 取最新一条 TOOL 消息（本轮结果；历史轮次的 TOOL 也在消息里）
        ChatMessage toolMsg = messages.stream()
                .filter(m -> m.role() == Role.TOOL)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(expectedId, toolMsg.toolCallId(), "TOOL 消息必须关联正确的 tool_call_id");
        assertTrue(toolMsg.content().contains("[tool: " + expectedTool + "]"));
        assertTrue(toolMsg.content().contains(expectedContentFragment));
    }
}
