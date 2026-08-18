package com.forgemind.tools.shell;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Shell 执行抽象：具体平台（cmd / powershell）各自实现命令拼装，
 * 进程生命周期统一由 {@link ProcessRunner} 管理。
 */
public interface ShellProvider {

    /**
     * 执行一条命令。
     *
     * @param command        命令文本
     * @param workingDirectory 进程工作目录
     * @param timeout        超时（超时则杀进程树）
     * @param maxOutputBytes stdout/stderr 每流输出上限（超限截断并标记）
     * @return 执行结果
     */
    ShellResult run(String command, Path workingDirectory, Duration timeout, long maxOutputBytes);
}
