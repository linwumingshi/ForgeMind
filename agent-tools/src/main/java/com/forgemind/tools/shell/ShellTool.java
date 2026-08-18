package com.forgemind.tools.shell;

import com.forgemind.core.config.ShellType;
import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * shell：在工作区目录下执行一条命令（SHELL 权限，默认询问）。
 *
 * <p>结果呈现（按确认的决策）：stdout 与 stderr 合并进 {@code output}，stderr
 * 用 {@code [stderr]} 分节标记；{@code error} 仅描述非零退出码或超时；
 * {@code exitCode} 与 {@code truncated} 保留；success = exitCode==0 && !timedOut。</p>
 */
public final class ShellTool implements AgentTool {

    private final ToolLimits limits;
    private final ShellProvider provider;

    public ShellTool() {
        this(ToolLimits.defaults());
    }

    public ShellTool(ToolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.provider = switch (limits.shellType()) {
            case CMD -> new CmdShellProvider();
            case POWERSHELL -> new PowerShellShellProvider();
        };
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Execute a shell command in the workspace directory. "
                + "Returns exit code, stdout and stderr.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "command", new ToolParameter("string", "command to execute")),
                List.of("command"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.SHELL;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Object rawCommand = arguments.get("command");
        String command = rawCommand instanceof String s ? s : "";
        if (command.isBlank()) {
            return ToolResult.failure("command must not be empty");
        }
        Path workDir = context.workspace().workspaceRoot();
        ShellResult result = provider.run(command, workDir,
                context.limits().shellTimeout(), context.limits().outputLimit());

        StringBuilder output = new StringBuilder();
        if (result.stdout() != null && !result.stdout().isEmpty()) {
            output.append(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isEmpty()) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append("[stderr]\n").append(result.stderr());
        }

        String error = null;
        if (result.timedOut()) {
            error = "command timed out after " + context.limits().shellTimeout();
        } else if (result.exitCode() != 0) {
            error = "exit code: " + result.exitCode();
        }
        boolean truncated = result.stdoutTruncated() || result.stderrTruncated();
        return new ToolResult(null, result.success(), output.toString(), error,
                result.exitCode(), truncated);
    }
}
