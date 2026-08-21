package com.forgemind.core.env;

import com.forgemind.core.config.ShellType;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Agent 运行环境信息（注入 system prompt，避免模型按错误平台的命令习惯盲试）。
 *
 * <p>纯函数、零系统依赖：{@code osName} 由调用方传入（生产传
 * {@code System.getProperty("os.name")}，测试可注入假值），保证可测且不污染生产代码。</p>
 */
public final class EnvironmentInfo {

    private EnvironmentInfo() {
    }

    /**
     * 生成精简的环境描述块（含 OS / Shell / Working directory / 命令执行规则）。
     *
     * @param osName   OS 名称（如 "Windows 11" / "Linux" / "Mac OS X"）
     * @param shellType 配置的 Shell 类型（null 按 CMD 处理）
     * @param workingDirectory 工作目录（自动取绝对路径）
     */
    public static String describe(String osName, ShellType shellType, Path workingDirectory) {
        StringBuilder sb = new StringBuilder();
        sb.append("Environment:\n");
        sb.append("- OS: ").append(osLabel(osName)).append('\n');
        sb.append("- Shell: ").append(shellLabel(osName, shellType)).append('\n');
        sb.append("- Working directory: ")
                .append(workingDirectory.toAbsolutePath().normalize()).append('\n');
        sb.append('\n');
        sb.append("Shell execution rules:\n");
        sb.append("- Use commands compatible with the current shell.\n");
        sb.append("- Do not assume Unix commands such as pwd, ls, grep, etc.\n");
        sb.append("- When a shell command fails, inspect the returned stderr/output "
                + "before trying another command.\n");
        sb.append("- Do not blindly repeat the same or equivalent command.\n");
        sb.append("- Prefer the smallest necessary verification command.\n");
        return sb.toString();
    }

    /** OS 名称归一化（win→Windows / mac→macOS / linux→Linux / 其他原样）。 */
    static String osLabel(String osName) {
        if (osName == null || osName.isBlank()) {
            return "Unknown";
        }
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return "Windows";
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return "macOS";
        }
        if (lower.contains("linux")) {
            return "Linux";
        }
        return osName;
    }

    /**
     * Java 项目工作流规则（P2.3）：注入 system prompt，帮助模型在 Java 项目上
     * 走"编译 → 运行"的正确路径，减少无效 Tool Call（未编译直接 java、
     * 用 PowerShell Add-Type 编译 Java 等）。
     *
     * <p>通用规则对所有平台生效；Windows 专属规则（cmd 语法 / Add-Type 禁用 /
     * 反斜杠路径）仅在 Windows 下注入。纯函数、零系统依赖，测试可注入 osName。</p>
     */
    public static String javaProjectRules(String osName, ShellType shellType) {
        StringBuilder sb = new StringBuilder();
        sb.append("Java project rules:\n");
        sb.append("- Compile before running: run javac first, e.g. \"javac -d . demo\\OrderDemo.java\", "
                + "then run \"java demo.OrderDemo\".\n");
        sb.append("- The source path mirrors the package: \"package demo;\" in demo\\OrderDemo.java "
                + "means the runnable class is demo.OrderDemo (dots, not slashes).\n");
        sb.append("- If \"Could not find or load main class\" (or \"找不到或无法加载主类\") appears, "
                + "do not just rename the main class. Check in order: (1) was javac run and do .class files "
                + "exist, (2) does the package match the directory structure, (3) is the classpath correct, "
                + "(4) is the working directory correct.\n");
        if ("Windows".equals(osLabel(osName))) {
            sb.append("- Do NOT use PowerShell Add-Type or other .NET commands to compile Java; "
                    + "javac is the Java compiler.\n");
            sb.append("- The shell is ").append(shellLabel(osName, shellType))
                    .append(": use its compatible syntax; backslash paths are fine, "
                            + "class names use dots.\n");
        }
        return sb.toString();
    }

    /**
     * Shell 名称归一化：Windows 按配置的 ShellType（cmd.exe / powershell.exe）；
     * 非 Windows 统一描述为 sh（现有 ShellProvider 仅支持 cmd/powershell，按真实习惯描述）。
     */
    static String shellLabel(String osName, ShellType shellType) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (!lower.contains("win")) {
            return "sh";
        }
        return switch (shellType == null ? ShellType.CMD : shellType) {
            case POWERSHELL -> "powershell.exe";
            case CMD -> "cmd.exe";
        };
    }
}
