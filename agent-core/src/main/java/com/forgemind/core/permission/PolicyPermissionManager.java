package com.forgemind.core.permission;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于策略的权限管理器。
 *
 * <p>默认按 scope 决策（READ → ALLOW，WRITE → ASK，SHELL → ASK），
 * 可按工具名精确覆盖（overrides 优先级高于 defaults）。
 * 未覆盖的 scope 缺省按 ASK 处理（安全默认，失败即询问）。
 * 实例不可变；{@link #withOverride} 返回新实例。</p>
 */
public final class PolicyPermissionManager implements PermissionManager {

    private final Map<PermissionScope, PermissionDecision> defaults;
    private final Map<String, PermissionDecision> overrides;

    public PolicyPermissionManager(Map<PermissionScope, PermissionDecision> defaults,
                                   Map<String, PermissionDecision> overrides) {
        this.defaults = Collections.unmodifiableMap(new EnumMap<>(defaults));
        this.overrides = Collections.unmodifiableMap(
                new HashMap<>(Objects.requireNonNull(overrides, "overrides")));
    }

    /** 使用默认策略创建：READ=ALLOW，WRITE=ASK，SHELL=ASK，COMMIT=ASK。 */
    public static PolicyPermissionManager withDefaults() {
        Map<PermissionScope, PermissionDecision> defaults = new EnumMap<>(PermissionScope.class);
        defaults.put(PermissionScope.READ, PermissionDecision.ALLOW);
        defaults.put(PermissionScope.WRITE, PermissionDecision.ASK);
        defaults.put(PermissionScope.SHELL, PermissionDecision.ASK);
        defaults.put(PermissionScope.COMMIT, PermissionDecision.ASK);
        return new PolicyPermissionManager(defaults, Map.of());
    }

    /** 返回新实例：为指定工具名添加（或覆盖）决策，原实例不变。 */
    public PolicyPermissionManager withOverride(String toolName, PermissionDecision decision) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(decision, "decision");
        Map<String, PermissionDecision> copy = new HashMap<>(overrides);
        copy.put(toolName, decision);
        return new PolicyPermissionManager(defaults, copy);
    }

    @Override
    public PermissionDecision decide(PermissionRequest request) {
        PermissionDecision override = overrides.get(request.toolName());
        if (override != null) {
            return override;
        }
        return defaults.getOrDefault(request.scope(), PermissionDecision.ASK);
    }
}
