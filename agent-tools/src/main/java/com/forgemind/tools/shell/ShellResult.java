package com.forgemind.tools.shell;

/**
 * Shell 命令执行结果。
 *
 * @param exitCode        退出码（超时/启动失败时为 -1）
 * @param stdout          stdout 内容（可能被截断）
 * @param stderr          stderr 内容（可能被截断）
 * @param timedOut        是否超时被杀
 * @param stdoutTruncated stdout 是否超过上限被截断
 * @param stderrTruncated stderr 是否超过上限被截断
 */
public record ShellResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean stdoutTruncated,
        boolean stderrTruncated) {

    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
