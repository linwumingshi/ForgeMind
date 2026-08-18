package com.forgemind.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 Tool 调用：LLM 决定调用某个工具并给出参数。
 *
 * <p>{@code id} 由 LLM 分配，用于把执行结果回灌到对应的 Tool Call。</p>
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments);
    }
}
