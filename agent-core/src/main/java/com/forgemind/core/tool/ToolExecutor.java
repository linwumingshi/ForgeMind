package com.forgemind.core.tool;

import com.forgemind.model.ToolResult;
import java.util.Map;

/**
 * Tool 执行器：负责"查找工具 → 参数校验 → 权限决策 → 执行"的完整链路。
 *
 * <p>所有失败都以错误 {@link ToolResult} 返回，不向 Agent 抛异常，
 * 让 LLM 能读到错误并自我纠正。</p>
 */
public interface ToolExecutor {

    /**
     * 执行一次 Tool 调用。
     *
     * @param toolName  工具名（必须已注册）
     * @param arguments 参数（可为 null，视为空参数）
     * @return 执行结果（成功或失败）
     */
    ToolResult execute(String toolName, Map<String, Object> arguments);
}
