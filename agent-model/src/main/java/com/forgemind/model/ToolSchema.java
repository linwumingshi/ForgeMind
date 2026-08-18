package com.forgemind.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool 参数 Schema（JSON Schema 的极小子集），用于参数校验与序列化为 LLM 的
 * tools 描述。
 *
 * <p>工具参数始终是 JSON 对象，因此 Schema 的 type 恒为 "object"
 * （{@link #TYPE_OBJECT}），由 LLM 适配层负责生成对外描述。</p>
 */
public record ToolSchema(
        Map<String, ToolParameter> properties,
        List<String> required) {

    /** JSON Schema 对象类型标识。 */
    public static final String TYPE_OBJECT = "object";

    public ToolSchema {
        properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        required = required == null ? List.of() : List.copyOf(required);
        for (String name : required) {
            if (!properties.containsKey(name)) {
                throw new IllegalArgumentException(
                        "required parameter not declared in properties: " + name);
            }
        }
    }

    public static ToolSchema of(Map<String, ToolParameter> properties, List<String> required) {
        return new ToolSchema(properties, required);
    }
}
