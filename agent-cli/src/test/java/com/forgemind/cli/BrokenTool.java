package com.forgemind.cli;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;

/**
 * 测试专用 Tool：执行时必然抛出构造时指定的异常（模拟 Tool 内部故障）。
 * 不属于正式生产 Tool。
 */
final class BrokenTool implements AgentTool {

    private final RuntimeException failure;

    BrokenTool(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public String name() {
        return "broken";
    }

    @Override
    public String description() {
        return "Always fails with a configured exception (test tool).";
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
        throw failure;
    }
}
