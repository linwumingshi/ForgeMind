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

class GitDiffToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final GitDiffTool tool = new GitDiffTool();

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
    }

    @Test
    void cleanRepoHasEmptyDiff() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().isEmpty());
    }

    @Test
    void modifiedFileShowsDiff() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "line1\nline2\n");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "line1\nchanged\n");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("a.txt"));
        assertTrue(result.output().contains("+changed"));
        assertTrue(result.output().contains("-line2"));
    }

    @Test
    void stagedFileShowsDiffOnlyWithStagedFlag() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "v2");
        GitTestSupport.git(workspace, "add", "a.txt");
        // 默认（working tree diff）：已 staged 内容不在工作区 diff 中
        ToolResult working = tool.execute(ctx, Map.of());
        assertTrue(working.success());
        assertTrue(working.output().isEmpty());
        // staged=true：显示已暂存差异
        ToolResult staged = tool.execute(ctx, Map.of("staged", true));
        assertTrue(staged.success());
        assertTrue(staged.output().contains("+v2"));
    }

    @Test
    void pathFiltersDiff() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a1");
        Files.writeString(workspace.resolve("b.txt"), "b1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "a2");
        Files.writeString(workspace.resolve("b.txt"), "b2");
        ToolResult result = tool.execute(ctx, Map.of("path", "a.txt"));
        assertTrue(result.success());
        assertTrue(result.output().contains("a.txt"));
        assertFalse(result.output().contains("b.txt"));
    }

    @Test
    void traversalRejected() {
        ToolResult result = tool.execute(ctx, Map.of("path", "../secret.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void absolutePathOutsideRejected() {
        ToolResult result = tool.execute(ctx, Map.of("path", tempDir.resolve("x.txt").toString()));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void optionInjectionPrevented() throws Exception {
        // 有 staged 修改；若 path="--staged" 被当作 git 选项则泄漏 staged diff
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("a.txt"), "v2");
        GitTestSupport.git(workspace, "add", "a.txt");
        ToolResult result = tool.execute(ctx, Map.of("path", "--staged"));
        // "--staged" 被当作字面路径（-- 分隔符），不触发 staged diff 注入
        assertFalse(result.output().contains("+v2"));
    }

    @Test
    void largeDiffTruncated() throws Exception {
        StringBuilder v1 = new StringBuilder();
        StringBuilder v2 = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            v1.append("line ").append(i).append('\n');
            v2.append("changed ").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("big.txt"), v1.toString());
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("big.txt"), v2.toString());
        ToolContext smallCtx = new ToolContext(new WorkspaceAccess(workspace),
                ToolLimits.defaults().withOutputLimit(256));
        ToolResult result = tool.execute(smallCtx, Map.of());
        assertTrue(result.success());
        assertTrue(result.truncated());
    }

    @Test
    void chineseFileNameShowsInDiff() throws Exception {
        Files.writeString(workspace.resolve("普通文件.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("普通文件.txt"), "v2");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("普通文件.txt"));
    }

    @Test
    void untrackedFileNotInDiff() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "v1");
        GitTestSupport.initRepo(workspace);
        GitTestSupport.commitAll(workspace, "init");
        Files.writeString(workspace.resolve("untracked.txt"), "u");
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertFalse(result.output().contains("untracked.txt"));
    }
}
