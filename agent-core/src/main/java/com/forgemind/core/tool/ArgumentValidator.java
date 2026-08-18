package com.forgemind.core.tool;

import com.forgemind.core.exception.InvalidToolArgumentsException;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool 参数的最小校验器（不引入重型 JSON Schema 框架）：
 * <ul>
 *   <li>required 参数必须存在且非 null；</li>
 *   <li>已声明的参数做基本类型检查（string/integer/boolean/array/object）；</li>
 *   <li>未声明的参数被忽略（容忍 LLM 的多余字段）。</li>
 * </ul>
 */
final class ArgumentValidator {

    private ArgumentValidator() {
    }

    static void validate(ToolSchema schema, Map<String, Object> arguments) {
        Objects.requireNonNull(schema, "schema");
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        for (String required : schema.required()) {
            if (args.get(required) == null) {
                throw new InvalidToolArgumentsException(
                        "missing required argument '" + required + "'");
            }
        }

        for (Map.Entry<String, ToolParameter> entry : schema.properties().entrySet()) {
            Object value = args.get(entry.getKey());
            if (value == null) {
                continue;
            }
            checkType(entry.getKey(), entry.getValue().type(), value);
        }
    }

    private static void checkType(String name, String expectedType, Object value) {
        boolean ok = switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true; // 未识别的类型声明不校验
        };
        if (!ok) {
            throw new InvalidToolArgumentsException(
                    "argument '" + name + "' must be of type " + expectedType
                            + ", got " + value.getClass().getSimpleName());
        }
    }
}
