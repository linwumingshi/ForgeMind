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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final WriteFileTool tool = new WriteFileTool();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    @Test
    void writesNewFileWithParentDirs() throws IOException {
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "src/main/java/Foo.java",
                "content", "class Foo {}"));
        assertTrue(result.success());
        assertTrue(result.error() == null);
        assertEquals("class Foo {}", Files.readString(workspace.resolve("src/main/java/Foo.java")));
        assertTrue(result.output().contains("wrote 12 bytes"));
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "old");
        ToolResult result = tool.execute(ctx, Map.of("path", "a.txt", "content", "new"));
        assertTrue(result.success());
        assertEquals("new", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void writesUtf8Content() throws IOException {
        ToolResult result = tool.execute(ctx, Map.of("path", "cn.txt", "content", "中文内容"));
        assertTrue(result.success());
        assertEquals("中文内容", Files.readString(workspace.resolve("cn.txt")));
    }

    @Test
    void rejectsParentTraversal() {
        ToolResult result = tool.execute(ctx, Map.of("path", "../evil.txt", "content", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsWorkspaceRootWrite() {
        ToolResult result = tool.execute(ctx, Map.of("path", ".", "content", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("cannot write to workspace root"));
    }

    @Test
    void rejectsSymlinkEscapeForNonExistentTarget() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        // 经逃逸链接写入不存在的目标文件：必须被拒绝
        ToolResult result = tool.execute(ctx, Map.of("path", "link/new.txt", "content", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
        assertFalse(Files.exists(outside.resolve("new.txt")), "外部文件不应被创建");
    }

    @Test
    void allowsWriteThroughInsideSymlink() throws IOException {
        Path realDir = Files.createDirectories(workspace.resolve("real"));
        Path link = workspace.resolve("alias");
        try {
            Files.createSymbolicLink(link, realDir);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        ToolResult result = tool.execute(ctx, Map.of("path", "alias/new.txt", "content", "ok"));
        assertTrue(result.success());
        assertEquals("ok", Files.readString(realDir.resolve("new.txt")));
    }
}
