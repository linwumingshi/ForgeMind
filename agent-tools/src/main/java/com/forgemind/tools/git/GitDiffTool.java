package com.forgemind.tools.git;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.ToolSupport;
import com.forgemind.tools.shell.ShellResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * git_diff：查看工作区（或已暂存）的变更（READ 权限）。
 *
 * <p>参数：{@code path}（可选，必须经 WorkspaceAccess 校验后转相对路径）、
 * {@code staged}（boolean）。命令固定为 {@code git diff [--staged] [-- <path>]}，
 * {@code --} 分隔符防止 path 被当作 Git 选项（option injection 防护）。
 * 输出受 ToolLimits.outputLimit 限制，超限截断并置 truncated。</p>
 */
public final class GitDiffTool implements AgentTool {

    @Override
    public String name() {
        return "git_diff";
    }

    @Override
    public String description() {
        return "Show the Git diff in the workspace (working tree by default, staged with staged=true).";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "path", new ToolParameter("string", "optional file/dir path inside workspace"),
                "staged", new ToolParameter("boolean", "show staged diff instead (default false)")),
                List.of());
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Path root = context.workspace().workspaceRoot();
        boolean staged = ToolSupport.boolArg(arguments, "staged", false);

        List<String> args = new ArrayList<>();
        args.add("diff");
        if (staged) {
            args.add("--staged");
        }
        Object rawPath = arguments.get("path");
        if (rawPath != null) {
            Path resolved = ToolSupport.resolvePath(context, rawPath);
            if (resolved == null) {
                return ToolResult.failure("path rejected: " + rawPath);
            }
            String rel = root.relativize(resolved).toString().replace('\\', '/');
            args.add("--");
            args.add(rel);
        }

        ShellResult result = GitProvider.run(root, context.limits(), args.toArray(String[]::new));
        if (result.exitCode() != 0) {
            return ToolResult.failure("git diff failed: " + gitError(result));
        }
        return new ToolResult(null, true, result.stdout(), null,
                result.exitCode(), result.stdoutTruncated());
    }

    private static String gitError(ShellResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        return stderr.isEmpty() ? "exit code " + result.exitCode() : stderr;
    }
}
