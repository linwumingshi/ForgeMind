package com.forgemind.core.tool;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.exception.ToolExecutionException;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import java.util.Map;

/**
 * Tool SPI：所有 Agent 工具（读文件、写文件、执行 Shell 等）都实现本接口，
 * 并以插件方式注册进 {@link ToolRegistry}。
 *
 * <p>Tool 只声明权限范围（{@link #permissionScope()}），不做任何权限决策；
 * 只通过 {@link ToolContext} 提供的受限访问操作文件系统。</p>
 */
public interface AgentTool {

    /** 唯一名称，LLM 通过它调用本工具，例如 "read_file"。 */
    String name();

    /** 给 LLM 阅读的能力描述。 */
    String description();

    /** 参数 Schema，用于参数校验与生成 LLM 的 tools 描述。 */
    ToolSchema schema();

    /** 本工具需要的权限范围；具体决策由 PermissionManager 完成。 */
    PermissionScope permissionScope();

    /**
     * 执行工具逻辑。参数在调用前已经过 Schema 校验。
     *
     * @throws ToolExecutionException 工具执行失败
     */
    ToolResult execute(ToolContext context, Map<String, Object> arguments) throws ToolExecutionException;
}
