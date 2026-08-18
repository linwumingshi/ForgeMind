package com.forgemind.model;

/**
 * Tool 执行结果。
 *
 * <p>{@code toolCallId} 关联产生该结果的 Tool Call；{@code exitCode} 仅 Shell 类
 * 工具使用；{@code truncated} 表示输出因超过上限被截断。</p>
 */
public record ToolResult(
        String toolCallId,
        boolean success,
        String output,
        String error,
        Integer exitCode,
        boolean truncated) {

    public static ToolResult success(String output) {
        return new ToolResult(null, true, output, null, null, false);
    }

    public static ToolResult success(String toolCallId, String output) {
        return new ToolResult(toolCallId, true, output, null, null, false);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(null, false, null, error, null, false);
    }

    public static ToolResult failure(String toolCallId, String error) {
        return new ToolResult(toolCallId, false, null, error, null, false);
    }

    /** 返回携带指定 toolCallId 的副本（其余字段不变）。 */
    public ToolResult withToolCallId(String id) {
        return new ToolResult(id, success, output, error, exitCode, truncated);
    }

    /** 返回携带指定 exitCode 的副本（其余字段不变）。 */
    public ToolResult withExitCode(int code) {
        return new ToolResult(toolCallId, success, output, error, code, truncated);
    }
}
