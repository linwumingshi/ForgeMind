package com.forgemind.core.permission;

import java.util.Objects;

/**
 * 一次权限请求：由 ToolExecutor 在调用工具前构造。
 *
 * @param scope       权限范围
 * @param toolName    发起请求的工具名
 * @param description 人类可读描述（如工具能力描述）
 * @param detail      关键载荷（目标路径 / shell 命令），供用户判断
 */
public record PermissionRequest(
        PermissionScope scope,
        String toolName,
        String description,
        String detail) {

    public PermissionRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(toolName, "toolName");
        description = description == null ? "" : description;
    }

    public static PermissionRequest of(PermissionScope scope, String toolName,
                                       String description, String detail) {
        return new PermissionRequest(scope, toolName, description, detail);
    }
}
