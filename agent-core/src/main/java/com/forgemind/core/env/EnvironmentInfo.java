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
