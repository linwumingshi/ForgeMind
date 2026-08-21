package com.forgemind.core.loop;

import com.forgemind.model.ToolResult;

/**
 * 观察/展示层进度监听器（可选能力）。
 *
 * <p>只用于 UI 增量展示，<b>不参与 AgentLoop 任何决策</b>：不执行 Tool、
 * 不修改 Context、不参与 Permission。所有方法默认 no-op；实现类抛出的异常
 * 会被 AgentLoop 忽略（记录日志），不得影响核心任务。</p>
 */
public interface ProgressListener {

    /** 流式文本增量（不进入 AgentContext，仅展示）。 */
    default void onTextDelta(String delta) {
    }

    /** 完整 Tool Call 即将执行（在 ToolExecutor 权限链之前通知）。 */
    default void onToolCallStarted(String toolName) {
    }

    /** Tool 执行结束（success 为最终结果标记）。 */
    default void onToolResult(String toolName, boolean success) {
    }

    /**
     * Tool 执行结束（携带完整 {@link ToolResult} 载荷，供展示层渲染
     * exitCode / stderr 摘要等失败细节）。
     *
     * <p>向后兼容：default 实现委托给 {@link #onToolResult(String, boolean)}，
     * 仅覆写旧方法的既有实现无需任何改动即可继续工作。</p>
     */
    default void onToolResult(String toolName, ToolResult result) {
        onToolResult(toolName, result.success());
    }

    /** M9：SubAgent 编排开始（子 Agent 创建并运行前回调；仅观察）。 */
    default void onSubAgentStarted(String task) {
    }

    /** M9：SubAgent 编排结束（success 为子 Agent 结果标记；仅观察）。 */
    default void onSubAgentResult(String task, boolean success) {
    }

    /** 无操作实例。 */
    ProgressListener NOOP = new ProgressListener() {
    };
}
