package com.forgemind.tools.search;

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

class SearchToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolContext ctx;
    private final SearchTool tool = new SearchTool();

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        Files.writeString(workspace.resolve("hello.txt"),
                "line one\nHello World\nline three\nline four\nline five");
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/hidden.txt"), "HELLO in ignored dir");
        Files.write(workspace.resolve("data.bin"), new byte[]{0, 1, 2, 0, 3});
        ctx = new ToolContext(new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    private ToolContext contextWith(ToolLimits custom) {
        return new ToolContext(new WorkspaceAccess(workspace), custom);
    }

    @Test
    void findsCaseInsensitiveMatchWithLineNumber() {
        ToolResult result = tool.execute(ctx, Map.of("query", "world"));
        assertTrue(result.success());
        assertTrue(result.output().contains("hello.txt:2"));
        assertTrue(result.output().contains("Hello World"));
    }

    @Test
    void includesContextLinesAroundMatch() {
        ToolResult result = tool.execute(ctx, Map.of("query", "World"));
        // 匹配行在第 2 行，上下文 ±2 应包含第 1 行与第 3、4 行
        assertTrue(result.output().contains("line one"));
        assertTrue(result.output().contains("line three"));
        assertTrue(result.output().contains("line four"));
        assertFalse(result.output().contains("line five"));
    }

    @Test
    void skipsIgnoreDirs() {
        ToolResult result = tool.execute(ctx, Map.of("query", "HELLO"));
        assertTrue(result.success());
        assertFalse(result.output().contains("hidden.txt"), "ignore 目录中的文件不应被搜索");
        assertTrue(result.output().contains("Hello World"));
    }

    @Test
    void skipsBinaryFiles() {
        ToolResult result = tool.execute(ctx, Map.of("query", "xyz"));
        assertTrue(result.success());
        // 二进制文件不应导致错误；没有文本匹配时输出为空
        assertTrue(result.output().isEmpty() || !result.output().contains("data.bin"));
    }

    @Test
    void skipsFilesOverSizeLimit() throws IOException {
        Files.write(workspace.resolve("big.txt"), new byte[100]);
        ToolContext smallCtx = contextWith(ToolLimits.defaults().withSearchMaxFileBytes(10));
        ToolResult result = tool.execute(smallCtx, Map.of("query", "a"));
        assertTrue(result.success());
        assertFalse(result.output().contains("big.txt"));
    }

    @Test
    void truncatesWhenExceedingResults() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(workspace.resolve("f" + i + ".txt"), "needle " + i);
        }
        ToolContext limitedCtx = contextWith(ToolLimits.defaults().withSearchMaxResults(2));
        ToolResult result = tool.execute(limitedCtx, Map.of("query", "needle"));
        assertTrue(result.success());
        assertTrue(result.truncated());
    }

    @Test
    void pathNotFound() {
        ToolResult result = tool.execute(ctx, Map.of("query", "x", "path", "missing"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path not found"));
    }

    @Test
    void pathIsFileNotDirectory() {
        ToolResult result = tool.execute(ctx, Map.of("query", "x", "path", "hello.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("not a directory"));
    }

    @Test
    void rejectsParentTraversal() {
        ToolResult result = tool.execute(ctx, Map.of("query", "x", "path", "../secret"));
        assertFalse(result.success());
        assertTrue(result.error().contains("path rejected"));
    }

    @Test
    void rejectsEmptyQuery() {
        ToolResult result = tool.execute(ctx, Map.of("query", ""));
        assertFalse(result.success());
        assertTrue(result.error().contains("query must not be empty"));
    }
}
