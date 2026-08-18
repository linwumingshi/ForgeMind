package com.forgemind.core.permission;

/**
 * 权限决策器：根据策略返回 ALLOW / ASK / DENY。
 * 策略本身不负责人类交互。
 */
public interface PermissionManager {

    PermissionDecision decide(PermissionRequest request);
}
