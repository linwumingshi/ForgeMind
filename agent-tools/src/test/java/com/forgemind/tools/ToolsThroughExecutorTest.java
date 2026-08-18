package com.forgemind.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.ToolResult;
import com.forgemind.tools.fs.EditFileTool;
import com.forgemind.tools.fs.ListFilesTool;
import com.forgemind.tools.fs.ReadFileTool;
import com.forgemind.tools.fs.WriteFileTool;
import com.forgemind.tools.search.SearchTool;
import com.forgemind.tools.shell.ShellTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 集成测试：6 个正式 Tool 经 DefaultToolExecutor 的完整链路
 * （查找 → 校验 → 权限 → 执行）。
 */
class ToolsThroughExecutorTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private InMemoryToolRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        registry = new InMemoryToolRegistry();
        registry.register(new ListFilesTool());
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new SearchTool());
        registry.register(new ShellTool());
    }

    private DefaultToolExecutor executor(PermissionAnswerer answerer) {
        return new DefaultToolExecutor(registry, PolicyPermissionManager.withDefaults(),
                answerer, new WorkspaceAccess(workspace), ToolLimits.defaults());
    }

    @Test
    void registersAllSixTools() {
        assertEquals(6, registry.size());
    }

    @Test
    void readScopeToolRunsWithoutAsking() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "content");
        ToolResult result = executor(req -> {
            throw new AssertionError("READ 工具不应触发权限询问");
        }).execute("read_file", Map.of("path", "a.txt"));
        assertTrue(result.success());
        assertTrue(result.output().contains("content"));
    }

    @Test
    void writeFileAsksAndAllows() {
        ToolResult result = executor(req -> true).execute("write_file",
                Map.of("path", "b.txt", "content", "hello"));
        assertTrue(result.success());
        assertTrue(Files.exists(workspace.resolve("b.txt")));
    }

    @Test
    void writeFileAsksAndDenies() {
        ToolResult result = executor(req -> false).execute("write_file",
                Map.of("path", "c.txt", "content", "hello"));
        assertFalse(result.success());
        assertTrue(result.error().contains("permission denied"));
        assertFalse(Files.exists(workspace.resolve("c.txt")));
    }

    @Test
    void shellDeniedNotExecuted() {
        ToolResult result = executor(req -> false).execute("shell", Map.of("command", "echo nope"));
        assertFalse(result.success());
        assertTrue(result.error().contains("permission denied"));
    }

    @Test
    void invalidArgumentsRejectedBeforePermission() {
        ToolResult result = executor(req -> {
            throw new AssertionError("参数错误时不应走到权限询问");
        }).execute("read_file", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("invalid arguments"));
    }

    @Test
    void listAndSearchRunDirectly() throws IOException {
        Files.writeString(workspace.resolve("findme.txt"), "unique token here");
        ToolResult list = executor(req -> false).execute("list_files", Map.of());
        assertTrue(list.success());
        assertTrue(list.output().contains("findme.txt"));

        ToolResult search = executor(req -> false).execute("search", Map.of("query", "unique"));
        assertTrue(search.success());
        assertTrue(search.output().contains("findme.txt:1"));
    }

    @Test
    void editFileThroughExecutor() throws IOException {
        Files.writeString(workspace.resolve("d.txt"), "alpha beta gamma");
        ToolResult result = executor(req -> true).execute("edit_file",
                Map.of("path", "d.txt", "oldText", "beta", "newText", "BETA"));
        assertTrue(result.success());
        assertEquals("alpha BETA gamma", Files.readString(workspace.resolve("d.txt")));
    }
}
