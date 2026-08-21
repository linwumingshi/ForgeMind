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
import java.nio.charset.StandardCharsets;
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
    void descriptionContainsJavaWindowsExample() {
        // P2.3：description 必须给模型可模仿的 Java Windows 最小范例
        String desc = new ShellTool(limits).description();
        assertTrue(desc.contains("javac -d . demo\\OrderDemo.java"),
                "应包含 javac 编译示例: " + desc);
        assertTrue(desc.contains("java demo.OrderDemo"), "应包含 java 运行示例: " + desc);
        assertTrue(desc.contains("compile first"), "应说明先编译后运行: " + desc);
        if (isWindows()) {
            assertTrue(desc.contains("cmd.exe"), "Windows 应指明 cmd.exe: " + desc);
            assertTrue(desc.contains("Add-Type"), "应警告勿用 PowerShell Add-Type 编译 Java: " + desc);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ---------- P2.3：真实 Windows Java 编译/运行场景 ----------

    @Test
    void javaCompileThenRunPackageWorkflow() throws IOException {
        // 完整闭环：demo/OrderDemo.java (package demo) → javac -d . → java demo.OrderDemo → OK
        Assumptions.assumeTrue(javacAvailable(), "javac not available on PATH, skipping");
        Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(workspace.resolve("demo/OrderDemo.java"),
                "package demo;\n"
                        + "public class OrderDemo {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"OK\");\n"
                        + "    }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        // 编译：javac -d . demo\OrderDemo.java
        ToolResult compile = new ShellTool(limits).execute(ctx,
                Map.of("command", "javac -d . demo\\OrderDemo.java"));
        assertTrue(compile.success(), "javac 应成功: " + compile.error() + " / " + compile.output());
        assertTrue(java.nio.file.Files.exists(workspace.resolve("demo/OrderDemo.class")),
                "javac 应生成 .class 文件");
        // 运行：java demo.OrderDemo（package-qualified）
        ToolResult run = new ShellTool(limits).execute(ctx, Map.of("command", "java demo.OrderDemo"));
        assertTrue(run.success(), "java 应成功: " + run.error() + " / " + run.output());
        assertTrue(run.output().contains("OK"), "stdout 应包含 OK: " + run.output());
    }

    @Test
    void javaRunWithoutCompileFailsWithStderr() throws IOException {
        // 未编译直接 java demo.OrderDemo → 必须失败并携带 stderr（主类找不到）
        Assumptions.assumeTrue(javacAvailable(), "javac not available on PATH, skipping");
        Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(workspace.resolve("demo/OrderDemo.java"),
                "package demo;\npublic class OrderDemo {\n"
                        + "    public static void main(String[] args) { System.out.println(\"OK\"); }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        ToolResult run = new ShellTool(limits).execute(ctx, Map.of("command", "java demo.OrderDemo"));
        assertFalse(run.success(), "未编译直接运行必须失败");
        assertEquals(1, run.exitCode());
        assertTrue(run.output().contains("[stderr]"), "失败应携带 [stderr] 分节: " + run.output());
        assertTrue(run.output().contains("找不到") || run.output().contains("Could not find")
                        || run.output().contains("Error"),
                "stderr 应含主类找不到类错误: " + run.output());
    }

    private static boolean javacAvailable() {
        try {
            Process p = new ProcessBuilder("javac", "-version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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
