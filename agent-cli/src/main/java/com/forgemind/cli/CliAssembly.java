package com.forgemind.cli;

import com.forgemind.core.Agent;
import com.forgemind.core.DefaultAgent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.ConfigException;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.tools.fs.EditFileTool;
import com.forgemind.tools.fs.ListFilesTool;
import com.forgemind.tools.fs.ReadFileTool;
import com.forgemind.tools.fs.WriteFileTool;
import com.forgemind.tools.git.GitCommitTool;
import com.forgemind.tools.git.GitDiffTool;
import com.forgemind.tools.git.GitStatusTool;
import com.forgemind.tools.search.SearchTool;
import com.forgemind.tools.shell.ShellTool;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI 组合根装配：把配置、LLM、工具、权限、围栏装配成可运行的 Agent。
 * 工作目录始终经 {@link WorkspaceAccess} 围栏；权限始终经 ToolExecutor 链路，
 * CLI 不绕过任何安全检查。
 */
public final class CliAssembly {

    private CliAssembly() {
    }

    /** 标准工具集（M7 起 9 个：6 文件/搜索/shell + git_status/git_diff/git_commit）。 */
    public static List<AgentTool> standardTools() {
        return List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new SearchTool(),
                new ShellTool(),
                new GitStatusTool(),
                new GitDiffTool(),
                new GitCommitTool());
    }

    /**
     * 校验 LLM 关键配置（真实 LLM 路径专用）。错误信息不含 Key 值。
     */
    public static void validateLlm(LlmConfig llm) {
        if (llm.apiKey() == null || llm.apiKey().isBlank()) {
            throw new ConfigException(
                    "missing API key: set FORGEMIND_API_KEY or provide it via --config");
        }
        if (llm.model() == null || llm.model().isBlank()) {
            throw new ConfigException("model is not configured: set it via --config");
        }
    }

    /**
     * 装配完整 Agent。
     *
     * @param config    Agent 配置（含 ToolLimits，单一配置来源）
     * @param llm       LLM 客户端（真实或测试 Fake）
     * @param workingDir 工作目录（经 WorkspaceAccess 围栏）
     * @param answerer  权限应答（交互 / --yes 自动允许）
     */
    public static Agent buildAgent(AgentConfig config, LlmClient llm,
                                   Path workingDir, PermissionAnswerer answerer) {
        return buildAgent(config, llm, workingDir, answerer, ProgressListener.NOOP);
    }

    /**
     * M8.5：装配完整 Agent，并注入 CLI 观察层 {@code progress}（如
     * {@link StreamingProgressRenderer}）。装配其余部分与 4 参版本完全一致。
     */
    public static Agent buildAgent(AgentConfig config, LlmClient llm,
                                   Path workingDir, PermissionAnswerer answerer,
                                   ProgressListener progress) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        standardTools().forEach(registry::register);
        WorkspaceAccess workspace = new WorkspaceAccess(workingDir);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), answerer, workspace, config.toolLimits());
        AgentLoop loop = new AgentLoop(workingDir, llm, registry, executor, config, progress);
        return new DefaultAgent(loop);
    }
}
