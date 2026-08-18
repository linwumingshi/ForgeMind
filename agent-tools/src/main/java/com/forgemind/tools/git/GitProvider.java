package com.forgemind.tools.git;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.tools.shell.ProcessRunner;
import com.forgemind.tools.shell.ShellResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Git 命令执行封装：统一 {@code git -C <workspace> ...}，复用 {@link ProcessRunner}
 * （超时、输出限制、进程树杀灭、UTF-8/GBK 双解码全部继承）。
 *
 * <p>安全：{@code workspace} 必须是经 WorkspaceAccess 校验后的 workspaceRoot，
 * 本类不接受任意用户路径；参数以数组透传，不做 shell 拼接。</p>
 */
final class GitProvider {

    private GitProvider() {
    }

    static ShellResult run(Path workspace, ToolLimits limits, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(workspace.toString());
        cmd.addAll(Arrays.asList(args));
        return ProcessRunner.run(cmd, workspace, limits.shellTimeout(), limits.outputLimit());
    }
}
