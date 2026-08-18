package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.permission.PermissionScope;
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
 * 完整 Coding Agent Git Commit 闭环：
 * git_status → read_file → edit_file → git_diff → git_commit → git_status → final。
 */
class AgentLoopGitCommitFlowTest {

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

    private String gitOutput(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", workspace.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);
        return out.trim();
    }

    private int commitCount() throws Exception {
        return Integer.parseInt(gitOutput("rev-list", "--count", "HEAD"));
    }

    private String lastCommitMessage() throws Exception {
        return gitOutput("log", "-1", "--pretty=%B");
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    @Test
    void statusReadEditDiffCommitStatusFlow() throws Exception {
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
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("m1", "git_commit", Map.of("message", "fix: return 2 instead of 1")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("s2", "git_status", Map.of()))))
                .then(AgentResponse.finalAnswer("bug fixed and committed"));

        int commitsBefore = commitCount();
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, workspace, req -> true);
        AgentResult result = agent.run("fix the bug and commit");
        assertTrue(result.finished());
        assertEquals("bug fixed and committed", result.finalAnswer());
        assertEquals(7, result.iterations());
        assertEquals(6, result.toolCallCount());

        // 1) 真实文件修改
        String content = Files.readString(workspace.resolve("Bug.java"), StandardCharsets.UTF_8);
        assertTrue(content.contains("return 2;"));
        assertFalse(content.contains("return 1;"));

        // 2) commit 真实创建且 message 正确
        assertEquals(commitsBefore + 1, commitCount());
        assertTrue(lastCommitMessage().contains("fix: return 2 instead of 1"));

        // 3) 最终 git_status clean（最后 TOOL 消息）
        ChatMessage lastStatus = fake.calls().get(6).stream()
                .filter(m -> m.role() == Role.TOOL).reduce((a, b) -> b).orElseThrow();
        assertTrue(lastStatus.content().contains("unstaged: []"));
        assertTrue(lastStatus.content().contains("untracked: []"));

        // 4) tool_call_id 全部正确关联
        assertToolCallRound(fake.calls().get(1), "s1", "git_status", "branch: main");
        assertToolCallRound(fake.calls().get(2), "r1", "read_file", "return 1;");
        assertToolCallRound(fake.calls().get(3), "e1", "edit_file", "replaced 1 occurrence");
        assertToolCallRound(fake.calls().get(4), "d1", "git_diff", "+    public int value() { return 2; }");
        assertToolCallRound(fake.calls().get(5), "m1", "git_commit", "committed main");
    }

    @Test
    void commitDeniedThenSelfCorrects() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("m1", "git_commit", Map.of("message", "should be denied")))))
                .then(AgentResponse.finalAnswer("permission rejected, not committed"));

        int commitsBefore = commitCount();
        // COMMIT 权限被拒绝；READ/WRITE/SHELL 均允许
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, workspace,
                req -> req.scope() != PermissionScope.COMMIT);
        AgentResult result = agent.run("commit something");
        assertTrue(result.finished(), "COMMIT DENY 不应导致 Agent 崩溃");
        assertEquals("permission rejected, not committed", result.finalAnswer());

        // commit 未执行
        assertEquals(commitsBefore, commitCount());
        // failure 回灌给 LLM
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"));
    }

    private static void assertToolCallRound(List<ChatMessage> messages, String expectedId,
                                            String expectedTool, String expectedContentFragment) {
        ChatMessage toolMsg = messages.stream()
                .filter(m -> m.role() == Role.TOOL)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(expectedId, toolMsg.toolCallId());
        assertTrue(toolMsg.content().contains("[tool: " + expectedTool + "]"));
        assertTrue(toolMsg.content().contains(expectedContentFragment));
    }
}
