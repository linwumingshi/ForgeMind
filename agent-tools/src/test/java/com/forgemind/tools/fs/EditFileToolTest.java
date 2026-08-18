package com.forgemind.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.model.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditFileToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final EditFileTool tool = new EditFileTool();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    @Test
    void replacesSingleOccurrence() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "hello world, hello again");
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "world", "newText", "ForgeMind"));
        assertTrue(result.success());
        assertEquals("hello ForgeMind, hello again", Files.readString(workspace.resolve("app.txt")));
    }

    @Test
    void replacesUtf8Text() throws IOException {
        Files.writeString(workspace.resolve("cn.txt"), "你好世界，你好");
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "cn.txt", "oldText", "世界", "newText", "ForgeMind"));
        assertTrue(result.success());
        assertEquals("你好ForgeMind，你好", Files.readString(workspace.resolve("cn.txt")));
    }

    @Test
    void oldTextNotFoundLeavesFileUntouched() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "original content");
        byte[] before = Files.readAllBytes(workspace.resolve("app.txt"));
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "missing", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("oldText not found"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(workspace.resolve("app.txt"))));
    }

    @Test
    void multipleMatchesLeaveFileUntouched() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "same same same");
        byte[] before = Files.readAllBytes(workspace.resolve("app.txt"));
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "same", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("matched multiple times"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(workspace.resolve("app.txt"))));
    }

    @Test
    void rejectsEmptyOldText() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "x");
        ToolResult result = tool.execute(ctx, Map.of("path", "app.txt", "oldText", "", "newText", "y"));
        assertFalse(result.success());
        assertTrue(result.error().contains("oldText must not be empty"));
    }

    @Test
    void fileNotFound() {
        ToolResult result = tool.execute(ctx, Map.of("path", "nope.txt", "oldText", "a", "newText", "b"));
        assertFalse(result.success());
        assertTrue(result.error().contains("file not found"));
    }

    @Test
    void isDirectoryError() {
        ToolResult result = tool.execute(ctx, Map.of("path", ".", "oldText", "a", "newText", "b"));
        assertFalse(result.success());
        assertTrue(result.error().contains("is a directory"));
    }

    @Test
    void rejectsTooLargeOldText() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "hello");
        ToolContext smallCtx = new ToolContext(new WorkspaceAccess(workspace),
                ToolLimits.defaults().withEditFileOldTextMaxBytes(5));
        ToolResult result = tool.execute(smallCtx, Map.of(
                "path", "app.txt", "oldText", "longlonglong", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("oldText too large"));
    }

    @Test
    void rejectsParentTraversal() {
        ToolResult result = tool.execute(ctx, Map.of("path", "../x.txt", "oldText", "a", "newText", "b"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }
}
