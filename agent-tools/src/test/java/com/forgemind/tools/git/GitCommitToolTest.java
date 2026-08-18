package com.forgemind.tools.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCommitToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final GitCommitTool tool = new GitCommitTool();

    @BeforeEach
    void setUp() throws Exception {
        GitTestSupport.assumeGitAvailable();
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    private void seedRepo(String initialFile, String content) throws Exception {
        Files.writeString(workspace.resolve(initialFile), content, StandardCharsets.UTF_8);
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "initial");
        Files.writeString(workspace.resolve(initialFile), content + " changed", StandardCharsets.UTF_8);
        GitTestSupport.git(workspace, "add", "-A");
    }

    @Test
    void commitSucceedsWithHash() throws Exception {
        seedRepo("a.txt", "v1");
        int before = GitTestSupport.commitCount(workspace);
        ToolResult result = tool.execute(ctx, Map.of("message", "fix bug"));
        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("committed main"));
        assertTrue(result.output().contains(GitTestSupport.lastCommitHash(workspace)));
        assertEquals(before + 1, GitTestSupport.commitCount(workspace));
    }

    @Test
    void messageWithSpaces() throws Exception {
        seedRepo("a.txt", "v1");
        ToolResult result = tool.execute(ctx, Map.of("message", "fix bug: add null check"));
        assertTrue(result.success(), result.error());
        assertEquals("fix bug: add null check", GitTestSupport.lastCommitMessage(workspace));
    }

    @Test
    void messageWithChinese() throws Exception {
        seedRepo("a.txt", "v1");
        ToolResult result = tool.execute(ctx, Map.of("message", "修复登录问题"));
        assertTrue(result.success(), result.error());
        assertTrue(GitTestSupport.lastCommitMessage(workspace).contains("修复登录问题"));
    }

    @Test
    void semicolonIsNotInjected() throws Exception {
        seedRepo("a.txt", "v1");
        String message = "hello; whoami";
        ToolResult result = tool.execute(ctx, Map.of("message", message));
        assertTrue(result.success(), result.error());
        assertEquals(message, GitTestSupport.lastCommitMessage(workspace));
    }

    @Test
    void doubleAmpersandIsNotInjected() throws Exception {
        seedRepo("a.txt", "v1");
        String message = "hello && whoami";
        ToolResult result = tool.execute(ctx, Map.of("message", message));
        assertTrue(result.success(), result.error());
        assertEquals(message, GitTestSupport.lastCommitMessage(workspace));
    }

    @Test
    void subshellIsNotInjected() throws Exception {
        seedRepo("a.txt", "v1");
        String message = "hello $(whoami)";
        ToolResult result = tool.execute(ctx, Map.of("message", message));
        assertTrue(result.success(), result.error());
        assertEquals(message, GitTestSupport.lastCommitMessage(workspace));
    }

    @Test
    void pipeIsNotInjected() throws Exception {
        seedRepo("a.txt", "v1");
        String message = "hello | whoami";
        ToolResult result = tool.execute(ctx, Map.of("message", message));
        assertTrue(result.success(), result.error());
        assertEquals(message, GitTestSupport.lastCommitMessage(workspace));
    }

    @Test
    void multilineMessageIsHandled() throws Exception {
        seedRepo("a.txt", "v1");
        String message = "title line\nbody line";
        ToolResult result = tool.execute(ctx, Map.of("message", message));
        assertTrue(result.success(), result.error());
        assertTrue(GitTestSupport.lastCommitMessage(workspace).contains("title line"));
        assertTrue(GitTestSupport.lastCommitMessage(workspace).contains("body line"));
    }

    @Test
    void noChangesFails() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "initial");
        int before = GitTestSupport.commitCount(workspace);
        ToolResult result = tool.execute(ctx, Map.of("message", "noop"));
        assertFalse(result.success());
        assertTrue(result.error().contains("git commit failed"));
        assertEquals(before, GitTestSupport.commitCount(workspace));
    }

    @Test
    void notAGitRepoFails() {
        ToolResult result = tool.execute(ctx, Map.of("message", "x"));
        assertFalse(result.success());
    }

    @Test
    void emptyMessageFails() {
        ToolResult result = tool.execute(ctx, Map.of("message", "   "));
        assertFalse(result.success());
        assertTrue(result.error().contains("message must not be empty"));
    }

    @Test
    void permissionDenyBlocksCommit() throws Exception {
        seedRepo("a.txt", "v1");
        int before = GitTestSupport.commitCount(workspace);
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), req -> false, new WorkspaceAccess(workspace));
        ToolResult result = executor.execute("git_commit", Map.of("message", "denied"));
        assertFalse(result.success());
        assertTrue(result.error().contains("permission denied"));
        assertEquals(before, GitTestSupport.commitCount(workspace), "DENY 时不得创建 commit");
    }

    @Test
    void permissionAskAllowCommits() throws Exception {
        seedRepo("a.txt", "v1");
        int before = GitTestSupport.commitCount(workspace);
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), req -> true, new WorkspaceAccess(workspace));
        ToolResult result = executor.execute("git_commit", Map.of("message", "allowed"));
        assertTrue(result.success());
        assertEquals(before + 1, GitTestSupport.commitCount(workspace));
    }

    @Test
    void workspaceOutsideRepoUnreachable() throws Exception {
        // workspace 是空目录（无 repo）；另一个目录是 repo——git -C 锁死 workspaceRoot
        Path otherRepo = Files.createDirectories(tempDir.resolve("other"));
        Files.writeString(otherRepo.resolve("x.txt"), "x", StandardCharsets.UTF_8);
        GitTestSupport.initRepo(otherRepo);
        GitTestSupport.commitAll(otherRepo, "init");
        ToolResult result = tool.execute(ctx, Map.of("message", "x"));
        assertFalse(result.success(), "workspace 内无 repo 时不能操作外部仓库");
    }
}
