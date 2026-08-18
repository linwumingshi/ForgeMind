package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
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
 * M8.4：Streaming 通道下的权限边界。stream → tool_call(git_commit) →
 * PermissionManager DENY → 错误回灌 → 第二轮 stream 自纠 → final。
 * Streaming 只是传输层，绝不绕过 Permission/WorkspaceAccess 决策。
 */
class AgentLoopStreamPermissionTest {

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

    private int commitCount() throws Exception {
        List<String> cmd = List.of("git", "-C", workspace.toString(),
                "rev-list", "--count", "HEAD");
        Process p = new ProcessBuilder(cmd).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);
        return Integer.parseInt(out.trim());
    }

    @Test
    void streamingCommitDeniedThenSelfCorrects() throws Exception {
        List<String> started = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("attempting commit",
                        List.of(ToolCall.of("m1", "git_commit", Map.of("message", "should be denied")))))
                .then(AgentResponse.finalAnswer("permission rejected, not committed"));

        int commitsBefore = commitCount();
        // 注入 git 工具；COMMIT DENY，READ/WRITE/SHELL 均允许
        AgentLoop agent = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(),
                req -> req.scope() != PermissionScope.COMMIT,
                new ProgressListener() {
                    @Override
                    public void onTextDelta(String delta) {
                        deltas.add(delta);
                    }

                    @Override
                    public void onToolCallStarted(String toolName) {
                        started.add(toolName);
                    }
                },
                CliAssembly.standardTools().stream()
                        .filter(t -> t.name().startsWith("git_"))
                        .toArray(com.forgemind.core.tool.AgentTool[]::new));

        AgentResult result = agent.run("commit something");
        assertTrue(result.finished(), "COMMIT DENY 不应导致 Agent 崩溃");
        assertEquals("permission rejected, not committed", result.finalAnswer());

        // 1) streaming 通道真实生效：两轮 content 均逐字符 delta 被观察到
        assertEquals("attempting commitpermission rejected, not committed",
                String.join("", deltas));

        // 2) tool 流经 AgentLoop 进度回调
        assertTrue(started.contains("git_commit"));

        // 3) commit 未执行
        assertEquals(commitsBefore, commitCount());

        // 4) failure（含 permission denied）回灌给 LLM，用于第二轮自纠
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"));
    }
}
