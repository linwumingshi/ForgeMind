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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final ReadFileTool tool = new ReadFileTool();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    private ToolContext contextWith(ToolLimits custom) {
        return new ToolContext(new WorkspaceAccess(workspace), custom);
    }

    @Test
    void readsUtf8Content() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "你好，ForgeMind\nsecond line");
        ToolResult result = tool.execute(ctx, Map.of("path", "hello.txt"));
        assertTrue(result.success());
        assertTrue(result.output().contains("你好，ForgeMind"));
        assertTrue(result.output().contains("second line"));
    }

    @Test
    void fileNotFound() {
        ToolResult result = tool.execute(ctx, Map.of("path", "missing.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("file not found"));
    }

    @Test
    void isDirectoryError() {
        ToolResult result = tool.execute(ctx, Map.of("path", "."));
        assertFalse(result.success());
        assertTrue(result.error().contains("is a directory"));
    }

    @Test
    void rejectsBinaryByNullByte() throws IOException {
        Files.write(workspace.resolve("data.bin"), new byte[]{1, 2, 0, 3, 4});
        ToolResult result = tool.execute(ctx, Map.of("path", "data.bin"));
        assertFalse(result.success());
        assertTrue(result.error().contains("binary file"));
    }

    @Test
    void rejectsBinaryByExtension() throws IOException {
        Files.writeString(workspace.resolve("image.png"), "not really an image");
        ToolResult result = tool.execute(ctx, Map.of("path", "image.png"));
        assertFalse(result.success());
        assertTrue(result.error().contains("binary file"));
    }

    @Test
    void rejectsFileTooLarge() throws IOException {
        Files.write(workspace.resolve("big.txt"), new byte[100]);
        ToolContext smallCtx = contextWith(ToolLimits.defaults().withReadFileMaxBytes(50));
        ToolResult result = tool.execute(smallCtx, Map.of("path", "big.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("file too large"));
    }

    @Test
    void stripsUtf8Bom() throws IOException {
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] rest = "content".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[withBom.length + rest.length];
        System.arraycopy(withBom, 0, all, 0, withBom.length);
        System.arraycopy(rest, 0, all, withBom.length, rest.length);
        Files.write(workspace.resolve("bom.txt"), all);
        ToolResult result = tool.execute(ctx, Map.of("path", "bom.txt"));
        assertTrue(result.success());
        assertEquals("content", result.output());
    }

    @Test
    void rejectsParentTraversal() {
        ToolResult result = tool.execute(ctx, Map.of("path", "../secret.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsAbsolutePathOutside() {
        ToolResult result = tool.execute(ctx, Map.of("path", tempDir.resolve("x.txt").toString()));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        ToolResult result = tool.execute(ctx, Map.of("path", "link/secret.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void missingPathDefaultsToWorkspaceRootAndFails() {
        // required 参数校验由 ToolExecutor（ArgumentValidator）负责；直接调用 Tool
        // 缺 path 时按工作区根解析 → 根是目录，返回失败
        ToolResult result = tool.execute(ctx, Map.of());
        assertFalse(result.success());
    }
}
