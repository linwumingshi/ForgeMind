package com.forgemind.core.context;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.fs.WorkspaceAccess;
import java.util.Objects;

/**
 * Tool 执行上下文：向 Tool 暴露受限的文件系统访问能力（WorkspaceAccess）
 * 与运行限额（ToolLimits），不暴露任何任意路径操作。
 */
public final class ToolContext {

    private final WorkspaceAccess workspace;
    private final ToolLimits limits;

    public ToolContext(WorkspaceAccess workspace, ToolLimits limits) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** 受限的工作区文件系统访问（路径围栏）。 */
    public WorkspaceAccess workspace() {
        return workspace;
    }

    /** 本工具运行限额（输出上限、超时、大小限制等）。 */
    public ToolLimits limits() {
        return limits;
    }
}
