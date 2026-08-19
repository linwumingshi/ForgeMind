package com.forgemind.core.subagent;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PermissionManager;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.core.tool.ToolRegistry;
import com.forgemind.model.AgentResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 默认 SubAgent 工厂（M9）：同步嵌套完整 AgentLoop。
 *
 * <p><b>安全不变量（必须保持）：</b></p>
 * <ul>
 *   <li>子 Agent 使用<b>独立新建</b>的 {@link InMemoryToolRegistry}，只注册白名单工具；</li>
 *   <li>白名单 ⊆ 主 registry；<b>永远不包含 {@code sub_agent}</b>（深度固定 1，结构上禁止递归）；</li>
 *   <li>tools=null/空 → 继承主 registry 全部工具，但仍排除 {@code sub_agent}；
 *       非空 → 每个名字必须 ∈ 主 registry，白名单外名字 → 返回失败结果（不抛异常）；</li>
 *   <li>子 Agent 复用<b>同一个</b> {@link LlmClient} / {@link PermissionManager} /
 *       {@link PermissionAnswerer} / {@link WorkspaceAccess} —— 每个工具调用仍完整经过
 *       ToolRegistry → ToolExecutor → PermissionManager → WorkspaceAccess → AgentTool，
 *       不存在"继承权限"或绕过 PermissionManager 的路径；</li>
 *   <li>子 Agent 使用独立 {@link com.forgemind.core.context.AgentContext}，不污染主 Context；</li>
 *   <li>子 Agent 的 maxIterations = spec 值（非 null），否则继承主 {@link AgentConfig}。</li>
 * </ul>
 *
 * <p>同步语义：同一线程阻塞运行完整子 AgentLoop，无并发、无线程池；
 * 线程中断自然传导（与主 Agent 共享取消语义）。</p>
 */
public final class DefaultSubAgentFactory implements SubAgentFactory {

    private final Path workingDirectory;
    private final LlmClient llm;
    private final ToolRegistry masterRegistry;
    private final PermissionManager permissionManager;
    private final PermissionAnswerer answerer;
    private final WorkspaceAccess workspace;
    private final AgentConfig config;

    public DefaultSubAgentFactory(Path workingDirectory,
                                  LlmClient llm,
                                  ToolRegistry masterRegistry,
                                  PermissionManager permissionManager,
                                  PermissionAnswerer answerer,
                                  WorkspaceAccess workspace,
                                  AgentConfig config) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.llm = Objects.requireNonNull(llm, "llm");
        this.masterRegistry = Objects.requireNonNull(masterRegistry, "masterRegistry");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager");
        this.answerer = Objects.requireNonNull(answerer, "answerer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public AgentResult run(SubAgentSpec spec) {
        Objects.requireNonNull(spec, "spec");

        InMemoryToolRegistry subRegistry = new InMemoryToolRegistry();
        List<String> rejected = new ArrayList<>();
        for (String name : resolveWhitelist(spec, rejected)) {
            // 从主 registry 复制工具实例（工具无状态，可共享）
            masterRegistry.find(name).ifPresent(subRegistry::register);
        }
        if (!rejected.isEmpty()) {
            return AgentResult.failed(null, 0, 0,
                    "subagent rejected: requested tools not available to subagent: " + rejected);
        }
        if (subRegistry.size() == 0) {
            return AgentResult.failed(null, 0, 0, "subagent rejected: no tools available");
        }

        AgentConfig subConfig = subConfig(spec);
        DefaultToolExecutor subExecutor = new DefaultToolExecutor(subRegistry,
                permissionManager, answerer, workspace, config.toolLimits());
        // 子 Agent 增量展示不在 M9.2 范围：子 loop 使用 no-op 观察层；
        // 子 Agent 开始/结束由 SubAgentTool 经主 ProgressListener 回调。
        AgentLoop subLoop = new AgentLoop(workingDirectory, llm, subRegistry, subExecutor,
                subConfig, ProgressListener.NOOP);
        return subLoop.run(spec.task());
    }

    @Override
    public int maxSubAgents() {
        return config.maxSubAgents();
    }

    /**
     * 解析白名单：返回可进入子 registry 的工具名；不合法名字写入 {@code rejected}。
     * 继承模式（tools=null/空）取主 registry 全部；白名单模式要求 requested ⊆ 主 registry。
     * <b>两种模式都强制排除 sub_agent</b>。
     */
    private List<String> resolveWhitelist(SubAgentSpec spec, List<String> rejected) {
        List<String> result = new ArrayList<>();
        if (spec.inheritsAllTools()) {
            for (String name : masterRegistry.all().keySet()) {
                if (!SUPERVISOR_TOOL.equals(name)) {
                    result.add(name);
                }
            }
            return result;
        }
        for (String requested : spec.tools()) {
            if (SUPERVISOR_TOOL.equals(requested)) {
                rejected.add(requested + " (sub_agent is not allowed inside subagent)");
                continue;
            }
            if (masterRegistry.find(requested).isEmpty()) {
                rejected.add(requested);
                continue;
            }
            result.add(requested);
        }
        return result;
    }

    /** 子 Agent 配置：maxIterations = spec 值（非 null）否则继承主；其余继承；maxSubAgents=0（无子能力）。 */
    private AgentConfig subConfig(SubAgentSpec spec) {
        int iterations = spec.maxIterations() != null ? spec.maxIterations() : config.maxIterations();
        return new AgentConfig(iterations, config.toolLimits(),
                config.contextMaxChars(), config.toolOutputLimit(),
                config.contextMaxTokens(), config.contextReserveTokens(),
                config.maxContinuationAttempts(), 0);
    }

    /** 编排工具名（与 SubAgentTool.name() 一致；两处分别持有避免循环依赖）。 */
    public static final String SUPERVISOR_TOOL = "sub_agent";
}
