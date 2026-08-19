package com.forgemind.core.subagent;

import com.forgemind.model.AgentResult;

/**
 * SubAgent 工厂（M9）：创建并同步运行子 Agent 的契约。
 *
 * <p>实现负责：</p>
 * <ul>
 *   <li>按 {@link SubAgentSpec} 构建子 Agent（独立白名单 ToolRegistry、
 *       同一 PermissionManager / WorkspaceAccess / answerer 安全链）；</li>
 *   <li>数量限制（{@link #maxSubAgents()}，一次主 run 内共享计数）；</li>
 *   <li>深度固定 1（M9）：子 Agent 白名单强制排除 sub_agent，结构上禁止递归；</li>
 *   <li>返回子 Agent 结果（失败以 {@link AgentResult#failed} 返回，不抛异常）。</li>
 * </ul>
 *
 * <p>同步语义：调用线程内阻塞运行完整子 AgentLoop；与主 Agent 共享线程，
 * 无并发、无线程池。取消（线程中断）自然传导（见 M9 设计文档）。</p>
 */
public interface SubAgentFactory {

    /**
     * 创建并同步运行一个子 Agent。
     *
     * @param spec 子 Agent 规格（非 null）
     * @return 子 Agent 运行结果；深度/数量超限或白名单非法时返回失败结果
     */
    AgentResult run(SubAgentSpec spec);

    /** 一次主 Agent 运行中允许创建的子 Agent 总数上限。 */
    int maxSubAgents();
}
