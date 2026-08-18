package com.forgemind.cli.logging;

import java.util.regex.Pattern;

/**
 * 最小日志脱敏工具：
 * <ul>
 *   <li>{@code sk-xxxx} 形式的 Key → {@code sk-***}；</li>
 *   <li>{@code Bearer xxx} / {@code Authorization: Bearer xxx} → {@code Bearer ***}；</li>
 *   <li>环境变量 {@code FORGEMIND_API_KEY} 的完整值动态替换（覆盖非 sk- 格式的 Key）。</li>
 * </ul>
 * 普通文本不受影响。不引入大型日志框架。
 */
public final class LogSanitizer {

    private static final Pattern SK_PATTERN = Pattern.compile("sk-[A-Za-z0-9_\\-]{6,}");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(\\bBearer\\s+)[^\\s,;\"'\\]]+");

    private LogSanitizer() {
    }

    /** 使用环境变量 FORGEMIND_API_KEY 作为动态 Key 脱敏。 */
    public static String sanitize(String message) {
        return sanitize(message, System.getenv("FORGEMIND_API_KEY"));
    }

    /**
     * 对消息脱敏。{@code apiKey} 为当前配置中的 Key 值（可为 null）。
     */
    public static String sanitize(String message, String apiKey) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String result = SK_PATTERN.matcher(message).replaceAll("sk-***");
        result = BEARER_PATTERN.matcher(result).replaceAll("$1***");
        if (apiKey != null && !apiKey.isEmpty() && apiKey.length() >= 4) {
            result = result.replace(apiKey, "***");
        }
        return result;
    }
}
