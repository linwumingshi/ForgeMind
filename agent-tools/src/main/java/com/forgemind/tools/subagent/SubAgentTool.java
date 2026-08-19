package com.forgemind.tools.subagent;

import com.forgemind.core.exception.ConfigException;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.subagent.DefaultSubAgentFactory;
import com.forgemind.core.subagent.SubAgentFactory;
import com.forgemind.core.subagent.SubAgentSpec;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.core.context.ToolContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * sub_agent：主 Agent 编排受限子 Agent 的工具（M9）。
 *
 * <p><b>权限语义：</b>{@code permissionScope=READ} 仅表示"编排 SubAgent 本身无副作用"；
 * 它<b>绝不授予或继承</b>子 Agent 的文件 / Shell / Commit 权限。子 Agent 内部每个工具
 * 调用仍独立经过 {@code ToolExecutor → PermissionManager → WorkspaceAccess} 完整安全链
 * （见 {@link DefaultSubAgentFactory} 安全不变量）。</p>
 *
 * <p>数量限制：{@code maxSubAgents} 是主 Agent 的全局预算 —— 一次主 Agent
 * {@code run()} 内所有 sub_agent 调用共享同一计数（M9.3：多次调用共享预算），
 * 超限返回 failure ToolResult（不抛异常、不执行新的子 Agent）。
 * 计数在 Agent 实例生命周期内累计（CLI 单次任务模式 = 一次 run；REPL 多轮
 * 任务共享计数，属保守方向）。</p>
 *
 * <p>渲染：子 Agent 结果（AgentResult）转为 ToolResult 文本回灌主 Agent；
 * 进入 Context 前的截断由 AgentLoop 的 ToolResultRenderer 统一完成，本工具不重复截断。</p>
 */
public final class SubAgentTool implements AgentTool {

    private final SubAgentFactory factory;
    private final ProgressListener progress;
    private int created;

    public SubAgentTool(SubAgentFactory factory, ProgressListener progress) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    @Override
    public String name() {
        return DefaultSubAgentFactory.SUPERVISOR_TOOL;
    }

    @Override
    public String description() {
        return "Spawn a restricted sub-agent to work on a delegated task in isolation, "
                + "then return its final result. The sub-agent can only use the tools "
                + "listed in 'tools' (or inherit the caller's tools when omitted) and "
                + "cannot spawn further sub-agents.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "task", new ToolParameter("string", "sub-task description (required)"),
                "tools", new ToolParameter("array",
                        "optional whitelist of tool names for the sub-agent; "
                                + "omit to inherit the caller's tools"),
                "maxIterations", new ToolParameter("integer",
                        "optional iteration budget for the sub-agent; omit to inherit")),
                List.of("task"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        SubAgentSpec spec;
        try {
            spec = buildSpec(arguments);
        } catch (ConfigException e) {
            return ToolResult.failure("sub_agent: " + e.getMessage());
        }

        if (++created > factory.maxSubAgents()) {
            created--;
            return ToolResult.failure("sub_agent: limit exceeded: at most "
                    + factory.maxSubAgents() + " sub-agents per run");
        }

        progress.onSubAgentStarted(spec.task());
        AgentResult result = factory.run(spec);
        progress.onSubAgentResult(spec.task(), result.finished());

        if (result.finished()) {
            return ToolResult.success(renderComplete(result));
        }
        return ToolResult.failure(renderFailed(result));
    }

    private static SubAgentSpec buildSpec(Map<String, Object> arguments) {
        String task = arguments.get("task") instanceof String s ? s : null;
        List<String> tools = parseTools(arguments.get("tools"));
        Integer maxIterations = parseMaxIterations(arguments.get("maxIterations"));
        return new SubAgentSpec(task, tools, maxIterations);
    }

    private static String renderComplete(AgentResult result) {
        return "[subagent:complete] final=" + (result.finalAnswer() == null ? "(none)" : result.finalAnswer())
                + " iterations=" + result.iterations()
                + " toolCalls=" + result.toolCallCount();
    }

    private static String renderFailed(AgentResult result) {
        return "[subagent:failed] error=" + (result.error() == null ? "unknown" : result.error())
                + " iterations=" + result.iterations()
                + " toolCalls=" + result.toolCallCount();
    }

    /** tools 参数：必须是 String 列表（null → 继承）；非 String 元素 → 拒绝。 */
    private static List<String> parseTools(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new ConfigException("tools must be a list of tool names");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new ConfigException("tools must contain only string tool names");
            }
            result.add(s);
        }
        return result;
    }

    /** maxIterations：必须是正整数（null → 继承）。 */
    private static Integer parseMaxIterations(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Long l && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        throw new ConfigException("maxIterations must be a positive integer");
    }
}
