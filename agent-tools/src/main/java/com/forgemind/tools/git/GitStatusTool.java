package com.forgemind.tools.git;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.shell.ShellResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * git_status：查看当前 Git 仓库状态（READ 权限）。
 *
 * <p>执行 {@code git status --porcelain=v1 -b}，返回结构化文本：branch / staged /
 * unstaged / untracked。工作目录锁死在 WorkspaceAccess 的 workspaceRoot；
 * 非 Git 仓库返回明确失败，不抛异常。</p>
 */
public final class GitStatusTool implements AgentTool {

    @Override
    public String name() {
        return "git_status";
    }

    @Override
    public String description() {
        return "Show the Git repository status in the workspace: branch, staged, unstaged, untracked.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(), List.of());
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        ShellResult result = GitProvider.run(
                context.workspace().workspaceRoot(), context.limits(),
                "status", "--porcelain=v1", "-b");
        if (result.exitCode() != 0) {
            return ToolResult.failure("git status failed: " + gitError(result));
        }
        return ToolResult.success(format(result.stdout()));
    }

    private static String gitError(ShellResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        return stderr.isEmpty() ? "exit code " + result.exitCode() : stderr;
    }

    /** 解析 porcelain v1 输出为结构化文本。 */
    static String format(String porcelain) {
        List<String> staged = new ArrayList<>();
        List<String> unstaged = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        String branch = "(detached)";
        if (porcelain == null || porcelain.isEmpty()) {
            return render(branch, staged, unstaged, untracked);
        }
        for (String line : porcelain.split("\\R")) {
            if (line.startsWith("## ")) {
                branch = parseBranch(line);
                continue;
            }
            if (line.length() < 3) {
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String path = line.substring(3);
            if (index == '?' && worktree == '?') {
                untracked.add(path);
            } else {
                if (index != ' ' && index != '?') {
                    staged.add(path);
                }
                if (worktree != ' ' && worktree != '?') {
                    unstaged.add(path);
                }
            }
        }
        return render(branch, staged, unstaged, untracked);
    }

    private static String parseBranch(String header) {
        String rest = header.substring(3); // 去掉 "## "
        String branch = rest.split("\\.\\.\\.")[0].trim();
        if (branch.contains("No commits yet on ")) {
            return branch.substring(branch.lastIndexOf(' ') + 1);
        }
        return branch.isEmpty() ? "(detached)" : branch;
    }

    private static String render(String branch, List<String> staged,
                                 List<String> unstaged, List<String> untracked) {
        return "branch: " + branch + "\n"
                + "staged: " + staged + "\n"
                + "unstaged: " + unstaged + "\n"
                + "untracked: " + untracked;
    }
}
