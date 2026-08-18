package com.forgemind.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ShellType;
import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.model.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellToolTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private ToolLimits limits;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        limits = ToolLimits.defaults();
        ctx = new ToolContext(new WorkspaceAccess(workspace), limits);
    }

    private ToolContext contextWith(ToolLimits custom) {
        return new ToolContext(new WorkspaceAccess(workspace), custom);
    }

    @Test
    void exitCodeZeroCapturesStdout() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "echo hello"));
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("hello"));
        assertFalse(result.truncated());
    }

    @Test
    void nonZeroExitCodeReported() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "exit 42"));
        assertFalse(result.success());
        assertEquals(42, result.exitCode());
        assertTrue(result.error().contains("exit code: 42"));
    }

    @Test
    void capturesStderrWithMarker() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "echo out & echo err 1>&2"));
        assertTrue(result.success());
        assertTrue(result.output().contains("out"));
        assertTrue(result.output().contains("[stderr]"));
        assertTrue(result.output().contains("err"));
    }

    @Test
    void timeoutKillsProcessTree() {
        ToolLimits fast = limits.withShellTimeout(Duration.ofMillis(800));
        ToolResult result = new ShellTool(fast).execute(contextWith(fast),
                Map.of("command", "ping -n 4 127.0.0.1 >nul"));
        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.error().contains("timed out"));
    }

    @Test
    void truncatesOutputOverLimit() {
        ToolLimits small = limits.withOutputLimit(64);
        ToolResult result = new ShellTool(small).execute(contextWith(small),
                Map.of("command", "for /L %i in (1,1,200) do @echo line%i"));
        assertTrue(result.truncated());
        assertTrue(result.output().length() <= 64);
        assertTrue(result.output().contains("line"));
    }

    @Test
    void usesWorkspaceAsWorkingDirectory() throws IOException {
        Files.writeString(workspace.resolve("probe.txt"), "x");
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "dir /b"));
        assertTrue(result.success());
        assertTrue(result.output().contains("probe.txt"));
    }

    @Test
    void rejectsEmptyCommand() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "   "));
        assertFalse(result.success());
        assertTrue(result.error().contains("command must not be empty"));
    }

    @Test
    void chineseStdoutIsDecodedAsUtf8() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "echo 你好世界"));
        assertTrue(result.success());
        assertTrue(result.output().contains("你好世界"), "stdout was: [" + result.output() + "]");
    }

    @Test
    void chineseStderrIsCaptured() {
        ToolResult result = new ShellTool(limits).execute(ctx, Map.of("command", "echo 错误信息 1>&2"));
        assertTrue(result.success());
        assertTrue(result.output().contains("[stderr]"));
        assertTrue(result.output().contains("错误信息"), "output was: [" + result.output() + "]");
    }

    @Test
    void powershellProviderSmoke() {
        Assumptions.assumeTrue(powershellAvailable(), "powershell not available");
        ToolLimits ps = limits.withShellType(ShellType.POWERSHELL);
        ToolResult result = new ShellTool(ps).execute(contextWith(ps), Map.of("command", "echo hello"));
        assertTrue(result.success());
        assertTrue(result.output().contains("hello"));
    }

    private static boolean powershellAvailable() {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "exit 0").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
