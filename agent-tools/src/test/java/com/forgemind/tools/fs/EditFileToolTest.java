package com.forgemind.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void oldTextNotFoundIncludesDiagnostics() throws IOException {
        // P2.3：not found 错误应包含可诊断提示（原因 + 建议 read_file 后重试）
        Files.writeString(workspace.resolve("app.txt"), "original content");
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "MISSING", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("oldText not found"));
        assertTrue(result.error().contains("case mismatch"), "应提示大小写不一致: " + result.error());
        assertTrue(result.error().contains("CRLF vs LF"), "应提示换行符不一致: " + result.error());
        assertTrue(result.error().contains("Re-read the file with read_file"),
                "应建议先 read_file 再重试: " + result.error());
    }

    @Test
    void multipleMatchesLeaveFileUntouched() throws IOException {
        Files.writeString(workspace.resolve("app.txt"), "same same same");
        byte[] before = Files.readAllBytes(workspace.resolve("app.txt"));
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "same", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("oldText matched"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(workspace.resolve("app.txt"))));
    }

    @Test
    void multipleMatchesReportsCountAndPrecisionHint() throws IOException {
        // P2.3：多匹配错误应报告次数并要求更精确的 oldText，绝不自动选择
        Files.writeString(workspace.resolve("app.txt"), "same same same");
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "app.txt", "oldText", "same", "newText", "x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("matched 3 times"), "应报告匹配次数: " + result.error());
        assertTrue(result.error().contains("more precise oldText"),
                "应要求更精确的 oldText: " + result.error());
        assertTrue(result.error().contains("matches exactly once"), "应明确唯一匹配要求: " + result.error());
    }

    @Test
    void crlfFileMismatchIsReportedWithDiagnostics() throws IOException {
        // P2.3：CRLF/LF 场景 —— 用 \n 的 oldText 匹配 \r\n 文件应失败且提示换行符问题
        Files.writeString(workspace.resolve("crlf.txt"), "line one\r\nline two\r\n", StandardCharsets.UTF_8);
        ToolResult result = tool.execute(ctx, Map.of(
                "path", "crlf.txt", "oldText", "line one\nline two", "newText", "merged"));
        assertFalse(result.success(), "CRLF 文件中用 LF oldText 不应匹配成功");
        assertTrue(result.error().contains("line-ending mismatch (CRLF vs LF)"),
                "应提示行尾不一致: " + result.error());
        // 失败不得修改文件
        assertEquals("line one\r\nline two\r\n",
                Files.readString(workspace.resolve("crlf.txt"), StandardCharsets.UTF_8));
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
