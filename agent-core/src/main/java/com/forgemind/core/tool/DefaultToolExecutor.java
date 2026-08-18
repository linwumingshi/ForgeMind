package com.forgemind.core.tool;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.exception.InvalidToolArgumentsException;
import com.forgemind.core.exception.ToolExecutionException;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PermissionDecision;
import com.forgemind.core.permission.PermissionManager;
import com.forgemind.core.permission.PermissionRequest;
import com.forgemind.model.ToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认 Tool 执行器：执行"查找 → 校验 → 权限 → 执行"链路。
 *
 * <p>权限决策：策略返回 ALLOW 直接执行；返回 ASK 时询问 {@link PermissionAnswerer}
 * （Answerer 不属于策略本身）；返回 DENY 或询问被拒绝则返回权限拒绝的错误结果。</p>
 */
public final class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolRegistry registry;
    private final PermissionManager permissionManager;
    private final PermissionAnswerer answerer;
    private final WorkspaceAccess workspace;
    private final ToolLimits limits;

    public DefaultToolExecutor(ToolRegistry registry,
                               PermissionManager permissionManager,
                               PermissionAnswerer answerer,
                               WorkspaceAccess workspace) {
        this(registry, permissionManager, answerer, workspace, ToolLimits.defaults());
    }

    public DefaultToolExecutor(ToolRegistry registry,
                               PermissionManager permissionManager,
                               PermissionAnswerer answerer,
                               WorkspaceAccess workspace,
                               ToolLimits limits) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager");
        this.answerer = Objects.requireNonNull(answerer, "answerer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Optional<AgentTool> found = registry.find(toolName);
        if (found.isEmpty()) {
            return ToolResult.failure("unknown tool: '" + toolName
                    + "'. available tools: " + registry.all().keySet());
        }
        AgentTool tool = found.get();
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        try {
            ArgumentValidator.validate(tool.schema(), args);
        } catch (InvalidToolArgumentsException e) {
            return ToolResult.failure(
                    "invalid arguments for tool '" + toolName + "': " + e.getMessage());
        }

        PermissionRequest request = PermissionRequest.of(
                tool.permissionScope(), tool.name(), tool.description(), extractDetail(args));
        PermissionDecision decision = permissionManager.decide(request);
        if (decision == PermissionDecision.ASK) {
            boolean allowed = answerer.ask(request);
            decision = allowed ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        }
        if (decision != PermissionDecision.ALLOW) {
            log.info("permission denied: {}", request);
            return ToolResult.failure("permission denied for tool '" + toolName
                    + "': " + request.description());
        }
        log.info("executing tool '{}' args={}", toolName, args);

        try {
            return tool.execute(new ToolContext(workspace, limits), args);
        } catch (ToolExecutionException e) {
            return ToolResult.failure("tool '" + toolName + "' failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure(
                    "tool '" + toolName + "' failed unexpectedly: " + e);
        }
    }

    /** 从参数中提取权限请求的关键载荷（path / command），供用户判断。 */
    private static String extractDetail(Map<String, Object> args) {
        for (String key : new String[]{"path", "command"}) {
            Object value = args.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
