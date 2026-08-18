package com.forgemind.core.testutil;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;

/**
 * 测试专用 Tool：名称与权限范围可配置，参数为可选的 "path"（用于验证
 * 权限请求的 detail 载荷提取）。
 */
public final class PathTool implements AgentTool {

    private final PermissionScope scope;

    public PathTool(PermissionScope scope) {
        this.scope = scope;
    }

    @Override
    public String name() {
        return "path_tool";
    }

    @Override
    public String description() {
        return "Accepts a path argument (test tool).";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(
                Map.of("path", new ToolParameter("string", "target path")),
                List.of());
    }

    @Override
    public PermissionScope permissionScope() {
        return scope;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        return ToolResult.success("resolved: " + arguments.get("path"));
    }
}
