package com.forgemind.tools.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStatusToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final GitStatusTool tool = new GitStatusTool();

    @BeforeEach
    void setUp() throws Exception {
        GitTestSupport.assumeGitAvailable();
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    @Test
    void notAGitRepoReturnsFailure() {
        ToolResult result = tool.execute(ctx, Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("not a git repository")
                || result.error().contains("failed"));
    }

    @Test
    void cleanRepoShowsBranch() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("branch: main"));
        assertTrue(result.output().contains("staged: []"));
        assertTrue(result.output().contains("unstaged: []"));
        assertTrue(result.output().contains("untracked: []"));
    }

    @Test
    void untrackedFileIsListed() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("new.txt"), "new");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("new.txt"));
        assertTrue(result.output().contains("untracked: [new.txt]"));
    }

    @Test
    void modifiedFileIsListedAsUnstaged() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "v2");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("unstaged: [a.txt]"));
        assertTrue(result.output().contains("staged: []"));
    }

    @Test
    void stagedFileIsListed() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "v2");
        GitTestSupport.git(workspace, "add", "a.txt");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("staged: [a.txt]"));
        assertTrue(result.output().contains("unstaged: []"));
    }

    @Test
    void stagedAndUntrackedCombined() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "v2");
        GitTestSupport.git(workspace, "add", "a.txt");
        Files.writeString(workspace.resolve("untracked.txt"), "u");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("staged: [a.txt]"));
        assertTrue(result.output().contains("untracked: [untracked.txt]"));
    }
}
