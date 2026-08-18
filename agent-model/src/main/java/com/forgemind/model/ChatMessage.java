package com.forgemind.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * 一条对话消息。
 *
 * <p>字段与 OpenAI-Compatible Chat Completions 消息对齐：ASSISTANT 消息可携带
 * {@link #toolCalls()}；TOOL 消息通过 {@link #toolCallId()} 关联对应的 Tool Call。</p>
 *
 * <p>null 字段（如 ASSISTANT 消息无内容、TOOL 消息无 toolCalls）序列化时被省略。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        Role role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null);
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, null, null, toolCalls);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, null);
    }
}
