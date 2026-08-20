package com.forgemind.core.testutil;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用 shell 工具：默认按预设失败次数返回失败（模拟真实 shell 失败，
 * 含 command 参数与 [stderr] 输出），超过失败预算后返回成功。
 *
 * <p>仅用于测试重复失败护栏，不属于正式生产 Tool。</p>
 */
public final class ControlledFailureTool implements AgentTool {

    private final AtomicInteger failuresLeft = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Always fails for the configured number of times (test tool).";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(
                Map.of("command", new ToolParameter("string", "command to execute")),
                List.of("command"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.SHELL;
    }

    /** 设置接下来的连续失败次数（默认无穷大 = 永远失败）。 */
    public void failNextTimes(int times) {
        failuresLeft.set(times);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        invocations.incrementAndGet();
        Object raw = arguments.get("command");
        String command = raw instanceof String s ? s : "";
        boolean shouldFail = failuresLeft.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0;
        if (shouldFail) {
            return new ToolResult(null, false, "[stderr]\nfailed: " + command,
                    "exit code: 1", 1, false);
        }
        return ToolResult.success("ok: " + command);
    }
}
