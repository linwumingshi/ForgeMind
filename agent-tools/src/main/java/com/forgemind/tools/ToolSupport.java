package com.forgemind.tools;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.exception.PathEscapeException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 各 Tool 共享的小工具方法（路径解析、参数类型转换）。
 */
public final class ToolSupport {

    private ToolSupport() {
    }

    /**
     * 解析路径参数：null → 工作区根；字符串 → 经 WorkspaceAccess 围栏解析。
     * 越界/非法路径返回 null（调用方转为失败 ToolResult）。
     */
    public static Path resolvePath(ToolContext context, Object rawPath) {
        if (rawPath == null) {
            return context.workspace().workspaceRoot();
        }
        try {
            return context.workspace().resolve((String) rawPath);
        } catch (PathEscapeException e) {
            return null;
        }
    }

    /** 布尔参数，缺省返回默认值。 */
    public static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    /** 整数参数，缺省返回默认值。 */
    public static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        return value instanceof Number n ? n.intValue() : defaultValue;
    }
}
