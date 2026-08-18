package com.forgemind.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

/**
 * LLM 的一次响应：要么包含最终文本（{@link #content()}），要么包含待执行的
 * Tool Call 列表（{@link #toolCalls()}），二者可同时存在（例如 content 为中间说明）。
 *
 * <p>{@code finishReason} 透传 OpenAI-Compatible 的 finish_reason（stop /
 * tool_calls / length 等），供 AgentLoop 感知截断等状态；null 表示未知。</p>
 *
 * <p>任务是否结束由 {@link #hasToolCalls()} 推导，避免出现"有 toolCalls 却
 * finished"的自相矛盾状态。</p>
 */
public record AgentResponse(String content, List<ToolCall> toolCalls, String finishReason) {

    public AgentResponse {
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** 兼容构造：finishReason 为 null。 */
    public AgentResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    /** 是否有待执行的 Tool Call。 */
    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 是否为最终答案（无 Tool Call）。 */
    @JsonIgnore
    public boolean isFinished() {
        return !hasToolCalls();
    }

    public static AgentResponse finalAnswer(String content) {
        return new AgentResponse(content, null, null);
    }

    public static AgentResponse withToolCalls(String content, List<ToolCall> toolCalls) {
        return new AgentResponse(content, toolCalls, null);
    }

    /** 携带 finishReason 的完整构造。 */
    public static AgentResponse withFinishReason(String content, List<ToolCall> toolCalls, String finishReason) {
        return new AgentResponse(content, toolCalls, finishReason);
    }
}
