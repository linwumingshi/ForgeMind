package com.forgemind.core.testutil;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;

/** 测试专用 Tool：名称可配置、无参数、直接成功。 */
public final class NamedTool implements AgentTool {

    private final String name;
    private final PermissionScope scope;

    public NamedTool(String name, PermissionScope scope) {
        this.name = name;
        this.scope = scope;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "No-op tool '" + name + "' (test tool).";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(), List.of());
    }

    @Override
    public PermissionScope permissionScope() {
        return scope;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        return ToolResult.success("ran: " + name);
    }
}
