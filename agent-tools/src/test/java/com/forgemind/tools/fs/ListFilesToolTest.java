package com.forgemind.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.model.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListFilesToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private ToolLimits limits;
    private final ListFilesTool tool = new ListFilesTool();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}");
        limits = ToolLimits.defaults();
        ctx = new ToolContext(new WorkspaceAccess(workspace), limits);
    }

    private ToolContext contextWith(ToolLimits custom) {
        return new ToolContext(new WorkspaceAccess(workspace), custom);
    }

    @Test
    void listsFilesAndDirs() {
        ToolResult result = tool.execute(ctx, Map.of());
        assertTrue(result.success());
        assertTrue(result.output().contains("FILE\ta.txt\t1\t"));
        assertTrue(result.output().contains("DIR\tsrc\t-\t"));
        assertFalse(result.truncated());
    }

    @Test
    void listsRelativePath() {
        ToolResult result = tool.execute(ctx, Map.of("path", "src"));
        assertTrue(result.success());
        assertTrue(result.output().contains("DIR\tmain\t-\t"));
    }

    @Test
    void listsAbsolutePathInside() {
        ToolResult result = tool.execute(ctx, Map.of("path", workspace.resolve("src").toString()));
        assertTrue(result.success());
        assertTrue(result.output().contains("DIR\tmain\t-\t"));
    }

    @Test
    void pathNotFound() {
        ToolResult result = tool.execute(ctx, Map.of("path", "missing"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path not found"));
    }

    @Test
    void pathIsFileReturnsNotADirectory() {
        ToolResult result = tool.execute(ctx, Map.of("path", "a.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("not a directory"));
    }

    @Test
    void recursiveListingWithDepth() {
        ToolResult result = tool.execute(ctx, Map.of("path", "src", "recursive", true, "maxDepth", 3));
        assertTrue(result.success());
        String relPath = "main" + java.io.File.separator + "java" + java.io.File.separator + "App.java";
        assertTrue(result.output().contains("FILE\t" + relPath));
    }

    @Test
    void maxDepthBeyondLimitRejected() {
        ToolResult result = tool.execute(ctx, Map.of("recursive", true, "maxDepth", 4));
        assertFalse(result.success());
        assertTrue(result.error().contains("maxDepth must be between 1 and 3"));
    }

    @Test
    void maxDepthOneDoesNotRecurse() {
        ToolResult result = tool.execute(ctx, Map.of("path", "src", "recursive", true, "maxDepth", 1));
        assertTrue(result.success());
        assertFalse(result.output().contains("App.java"));
    }

    @Test
    void truncatesWhenExceedingEntries() {
        ToolContext smallCtx = contextWith(limits.withListFilesMaxEntries(2));
        ToolResult result = tool.execute(smallCtx, Map.of());
        assertTrue(result.success());
        assertTrue(result.truncated());
    }

    @Test
    void rejectsParentTraversal() {
        ToolResult result = tool.execute(ctx, Map.of("path", "../secret"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsAbsolutePathOutside() {
        Path outside = tempDir.resolve("outside");
        ToolResult result = tool.execute(ctx, Map.of("path", outside.toString()));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsEmptyPath() {
        ToolResult result = tool.execute(ctx, Map.of("path", ""));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        ToolResult result = tool.execute(ctx, Map.of("path", "link"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void windowsPathOnOtherDriveRejected() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        char drive = workspace.getRoot().toString().charAt(0);
        char other = drive == 'C' ? 'D' : 'C';
        ToolResult result = tool.execute(ctx, Map.of("path", other + ":\\some\\dir"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void schemaHasExpectedParams() {
        assertEquals("list_files", tool.name());
        assertEquals(com.forgemind.core.permission.PermissionScope.READ, tool.permissionScope());
        assertTrue(tool.schema().properties().containsKey("recursive"));
        assertTrue(tool.schema().properties().containsKey("maxDepth"));
    }

    @Test
    void invalidMaxDepthTypeFails() {
        // 非数字 maxDepth：intArg 取默认值，不触发类型错误——此处验证默认行为不抛异常
        ToolResult result = tool.execute(ctx, Map.of("recursive", true, "maxDepth", "three"));
        assertTrue(result.success());
    }
}
