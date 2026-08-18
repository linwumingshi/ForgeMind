package com.forgemind.tools.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Windows cmd.exe 执行器（默认，兼容 mvn/git 等）。
 *
 * <p>注意：曾尝试加 {@code chcp 65001} 前缀缓解中文乱码，实测在本环境（Java
 * ProcessBuilder 子进程）反而引入无法解码的字节（U+FFFD），已回退为原样执行。
 * 中文输出正确性依赖环境控制台代码页——已知问题，见 architecture.md §19。</p>
 */
public final class CmdShellProvider implements ShellProvider {

    @Override
    public ShellResult run(String command, Path workingDirectory, Duration timeout, long maxOutputBytes) {
        List<String> cmd = List.of("cmd.exe", "/D", "/S", "/C", command);
        return ProcessRunner.run(cmd, workingDirectory, timeout, maxOutputBytes);
    }
}
