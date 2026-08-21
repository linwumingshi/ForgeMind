package com.forgemind.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ShellType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnvironmentInfoTest {

    private static final Path WORK_DIR = Path.of("C:/workspace/project");

    @Test
    void windowsWithCmdShell() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: Windows"));
        assertTrue(text.contains("- Shell: cmd.exe"));
        assertTrue(text.contains("- Working directory: C:\\workspace\\project"));
    }

    @Test
    void windowsWithPowerShell() {
        String text = EnvironmentInfo.describe("Windows 10", ShellType.POWERSHELL, WORK_DIR);
        assertTrue(text.contains("- Shell: powershell.exe"));
    }

    @Test
    void linuxUsesSh() {
        String text = EnvironmentInfo.describe("Linux", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: Linux"));
        assertTrue(text.contains("- Shell: sh"));
    }

    @Test
    void macOSUsesSh() {
        String text = EnvironmentInfo.describe("Mac OS X", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: macOS"));
        assertTrue(text.contains("- Shell: sh"));
    }

    @Test
    void unknownOsKeepsOriginalLabel() {
        assertEquals("FreeBSD", EnvironmentInfo.osLabel("FreeBSD"));
    }

    @Test
    void nullOsNameFallsBackToUnknown() {
        assertEquals("Unknown", EnvironmentInfo.osLabel(null));
        assertTrue(EnvironmentInfo.describe(null, ShellType.CMD, WORK_DIR).contains("- OS: Unknown"));
    }

    @Test
    void containsShellExecutionRules() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("Shell execution rules:"));
        assertTrue(text.contains("Do not assume Unix commands such as pwd, ls, grep, etc."));
        assertTrue(text.contains("inspect the returned stderr/output"));
        assertTrue(text.contains("Do not blindly repeat the same or equivalent command."));
        assertTrue(text.contains("Prefer the smallest necessary verification command."));
    }

    @Test
    void workingDirectoryIsNormalizedToAbsolute() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, Path.of("relative/dir"));
        assertTrue(text.contains("- Working directory: "));
        // 相对路径会被转绝对路径，不可能再出现 "relative/dir"
        assertFalse(text.contains("relative/dir"));
    }

    // ---------- P2.3：Java 项目工作流规则 ----------

    @Test
    void javaRulesMentionCompileThenRunWorkflow() {
        String rules = EnvironmentInfo.javaProjectRules("Windows 11", ShellType.CMD);
        assertTrue(rules.contains("Java project rules:"));
        assertTrue(rules.contains("javac"), "应提及 javac 编译");
        assertTrue(rules.contains("java demo.OrderDemo"), "应给出运行类示例");
        assertTrue(rules.contains("javac -d . demo\\OrderDemo.java"), "应给出 Windows 编译示例");
        assertTrue(rules.contains("runnable class is demo.OrderDemo"), "应说明 package↔目录对应");
        assertTrue(rules.contains("package"), "应提及 package 与目录对应关系");
    }

    @Test
    void javaRulesWarnAgainstAddTypeOnWindows() {
        String rules = EnvironmentInfo.javaProjectRules("Windows 11", ShellType.CMD);
        assertTrue(rules.contains("Add-Type"), "Windows 下应警告勿用 Add-Type 编译 Java");
        assertTrue(rules.contains("cmd.exe"), "Windows 下应指明 shell 为 cmd.exe");
    }

    @Test
    void javaRulesGiveMainClassDiagnostics() {
        String rules = EnvironmentInfo.javaProjectRules("Windows 11", ShellType.CMD);
        assertTrue(rules.contains("Could not find or load main class"),
                "应包含主类找不到的典型错误文案");
        assertTrue(rules.contains("was javac run"), "诊断方向应包含是否已编译");
        assertTrue(rules.contains(".class files"), "诊断方向应包含 .class 文件");
        assertTrue(rules.contains("classpath"), "诊断方向应包含 classpath");
    }

    @Test
    void javaRulesArePlatformAware() {
        // 非 Windows：仍给编译→运行与主类诊断，但不含 Add-Type/cmd 专属提示
        String linuxRules = EnvironmentInfo.javaProjectRules("Linux", ShellType.CMD);
        assertTrue(linuxRules.contains("javac"), "非 Windows 也应给 Java 编译指引");
        assertTrue(linuxRules.contains("Could not find or load main class"));
        assertFalse(linuxRules.contains("Add-Type"), "非 Windows 不应出现 Add-Type 提示");
        assertFalse(linuxRules.contains("cmd.exe"), "非 Windows 不应指明 cmd.exe");
    }

    @Test
    void existingEnvironmentRulesUnchangedByJavaRules() {
        // P2.3 只新增 Java 规则，原 Environment 规则必须保持
        String env = EnvironmentInfo.describe("Windows 11", ShellType.CMD, WORK_DIR);
        assertTrue(env.contains("Shell execution rules:"));
        assertTrue(env.contains("Do not assume Unix commands such as pwd, ls, grep, etc."));
        assertTrue(env.contains("Do not blindly repeat the same or equivalent command."));
        assertFalse(env.contains("Java project rules:"), "Java 规则是独立块，不应混入 Environment 块");
    }
}
