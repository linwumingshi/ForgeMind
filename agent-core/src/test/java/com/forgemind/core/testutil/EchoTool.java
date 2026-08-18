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
 * 测试专用 Echo Tool：把 "text" 参数原样回显，并记录调用次数。
 * 仅用于测试，不属于正式生产 Tool。
 */
public final class EchoTool implements AgentTool {

    private final PermissionScope scope;
    private final AtomicInteger invocations = new AtomicInteger();

    public EchoTool() {
        this(PermissionScope.READ);
    }

    public EchoTool(PermissionScope scope) {
        this.scope = scope;
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Echo the text argument back (test tool).";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(
                Map.of("text", new ToolParameter("string", "text to echo")),
                List.of("text"));
    }

    @Override
    public PermissionScope permissionScope() {
        return scope;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        invocations.incrementAndGet();
        return ToolResult.success("echo: " + arguments.get("text"));
    }

    public int invocationCount() {
        return invocations.get();
    }
}
