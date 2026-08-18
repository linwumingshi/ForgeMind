package com.forgemind.model;

import java.util.Objects;

/**
 * 单个 Tool 参数的 JSON Schema 描述（MVP 最小子集）。
 *
 * <p>{@code type} 取值与 JSON Schema 基本类型对齐：string / integer / boolean / array / object。</p>
 */
public record ToolParameter(String type, String description) {

    public ToolParameter {
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
    }
}
