package com.forgemind.tools.git;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.shell.ShellResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * git_commit：在 workspace 的 Git 仓库创建 commit（COMMIT 权限，默认询问）。
 *
 * <p>执行 {@code git -C <workspaceRoot> commit -m <message>}；message 作为
 * <b>独立进程参数</b> 传递（经 ProcessRunner 数组透传，无 shell 拼接，注入安全）。
 * 不支持 --amend / --no-verify / --author 等（第一版仅 -m）。</p>
 */
public final class GitCommitTool implements AgentTool {

    private static final int MAX_MESSAGE_LENGTH = 5000;
    private static final Pattern COMMIT_HEADER = Pattern.compile("^\\[(\\S+)\\s+([0-9a-f]+)\\]");

    @Override
    public String name() {
        return "git_commit";
    }

    @Override
    public String description() {
        return "Create a Git commit in the workspace repository with the given message. "
                + "Requires COMMIT permission. Use git_status / git_diff to verify before committing.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "message", new ToolParameter("string", "commit message")),
                List.of("message"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.COMMIT;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Object rawMessage = arguments.get("message");
        String message = rawMessage instanceof String s ? s : "";
        if (message.trim().isEmpty()) {
            return ToolResult.failure("message must not be empty");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ToolResult.failure("message too long: " + message.length()
                    + " chars (limit " + MAX_MESSAGE_LENGTH + ")");
        }

        // Coding Agent 语义：先暂存全部工作区变更，再提交（均经 ProcessRunner 参数透传）
        Path root = context.workspace().workspaceRoot();
        ShellResult addResult = GitProvider.run(root, context.limits(), "add", "-A");
        if (addResult.exitCode() != 0) {
            return ToolResult.failure("git add failed: " + gitError(addResult));
        }

        ShellResult result = GitProvider.run(root, context.limits(), "commit", "-m", message);
        if (result.exitCode() != 0) {
            return ToolResult.failure("git commit failed: " + gitError(result));
        }

        // 解析 "[branch hash] message" 输出
        String branch = "(unknown)";
        String hash = "(unknown)";
        String output = result.stdout() == null ? "" : result.stdout();
        Matcher matcher = COMMIT_HEADER.matcher(output.trim());
        if (matcher.find()) {
            branch = matcher.group(1);
            hash = matcher.group(2);
        }
        return ToolResult.success("committed " + branch + " " + hash + ": " + message);
    }

    private static String gitError(ShellResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        return stderr.isEmpty() ? "exit code " + result.exitCode() : stderr;
    }
}
