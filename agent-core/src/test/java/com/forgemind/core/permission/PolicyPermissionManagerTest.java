package com.forgemind.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyPermissionManagerTest {

    private final PolicyPermissionManager defaults = PolicyPermissionManager.withDefaults();

    private static PermissionRequest request(PermissionScope scope, String toolName) {
        return PermissionRequest.of(scope, toolName, "test", null);
    }

    @Test
    void defaultReadIsAllowed() {
        assertEquals(PermissionDecision.ALLOW, defaults.decide(request(PermissionScope.READ, "list_files")));
    }

    @Test
    void defaultWriteAsks() {
        assertEquals(PermissionDecision.ASK, defaults.decide(request(PermissionScope.WRITE, "write_file")));
    }

    @Test
    void defaultShellAsks() {
        assertEquals(PermissionDecision.ASK, defaults.decide(request(PermissionScope.SHELL, "shell")));
    }

    @Test
    void defaultCommitAsks() {
        assertEquals(PermissionDecision.ASK, defaults.decide(request(PermissionScope.COMMIT, "git_commit")));
    }

    @Test
    void overrideDeniesCommitByToolName() {
        PolicyPermissionManager policy = defaults.withOverride("git_commit", PermissionDecision.DENY);
        assertEquals(PermissionDecision.DENY, policy.decide(request(PermissionScope.COMMIT, "git_commit")));
    }

    @Test
    void overrideDeniesShell() {
        PolicyPermissionManager policy = defaults.withOverride("shell", PermissionDecision.DENY);
        assertEquals(PermissionDecision.DENY, policy.decide(request(PermissionScope.SHELL, "shell")));
    }

    @Test
    void overrideAllowsWrite() {
        PolicyPermissionManager policy = defaults.withOverride("writer", PermissionDecision.ALLOW);
        assertEquals(PermissionDecision.ALLOW, policy.decide(request(PermissionScope.WRITE, "writer")));
    }

    @Test
    void overrideTakesPrecedenceOverScope() {
        PolicyPermissionManager policy = defaults.withOverride("reader", PermissionDecision.DENY);
        assertEquals(PermissionDecision.DENY, policy.decide(request(PermissionScope.READ, "reader")));
    }

    @Test
    void overrideIsKeyedByToolNameNotScope() {
        // 只覆盖名为 "shell" 的工具；其他 SHELL 工具仍按默认 ASK
        PolicyPermissionManager policy = defaults.withOverride("shell", PermissionDecision.ALLOW);
        assertEquals(PermissionDecision.ALLOW, policy.decide(request(PermissionScope.SHELL, "shell")));
        assertEquals(PermissionDecision.ASK, policy.decide(request(PermissionScope.SHELL, "shell_other")));
    }

    @Test
    void missingScopeDefaultFallsBackToAsk() {
        Map<PermissionScope, PermissionDecision> partial = new EnumMap<>(PermissionScope.class);
        partial.put(PermissionScope.READ, PermissionDecision.ALLOW);
        PolicyPermissionManager policy = new PolicyPermissionManager(partial, Map.of());
        assertEquals(PermissionDecision.ASK, policy.decide(request(PermissionScope.WRITE, "w")));
    }

    @Test
    void withOverrideDoesNotMutateOriginal() {
        PolicyPermissionManager policy = defaults.withOverride("shell", PermissionDecision.DENY);
        assertEquals(PermissionDecision.ASK, defaults.decide(request(PermissionScope.SHELL, "shell")));
        assertEquals(PermissionDecision.DENY, policy.decide(request(PermissionScope.SHELL, "shell")));
    }
}
