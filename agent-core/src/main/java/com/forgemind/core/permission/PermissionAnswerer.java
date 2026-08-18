package com.forgemind.core.permission;

/**
 * 当策略返回 ASK 时，由 Answerer 完成人类交互（或自动化决策）。
 * Answerer 不属于 Policy 本身，由组合根注入。
 */
public interface PermissionAnswerer {

    /**
     * 询问是否允许本次请求。
     *
     * @return true 表示允许
     */
    boolean ask(PermissionRequest request);
}
