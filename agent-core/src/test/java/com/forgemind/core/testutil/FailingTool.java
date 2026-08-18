package com.forgemind.core.testutil;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.exception.ToolExecutionException;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;

/** 测试专用 Tool：执行时必然抛出 ToolExecutionException。 */
public final class FailingTool implements AgentTool {

    @Override
    public String name() {
        return "fail";
    }

    @Override
    public String description() {
        return "Always fails (test tool).";
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
        throw new ToolExecutionException("boom");
    }
}
