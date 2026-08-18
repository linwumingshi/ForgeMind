package com.forgemind.tools.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Windows PowerShell 执行器（通过 {@code agent.tools.shell.type=powershell} 启用）。
 */
public final class PowerShellShellProvider implements ShellProvider {

    @Override
    public ShellResult run(String command, Path workingDirectory, Duration timeout, long maxOutputBytes) {
        List<String> cmd = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", command);
        return ProcessRunner.run(cmd, workingDirectory, timeout, maxOutputBytes);
    }
}
