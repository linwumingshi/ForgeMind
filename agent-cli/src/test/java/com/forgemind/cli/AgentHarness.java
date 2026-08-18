package com.forgemind.cli;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.tools.fs.EditFileTool;
import com.forgemind.tools.fs.ListFilesTool;
import com.forgemind.tools.fs.ReadFileTool;
import com.forgemind.tools.fs.WriteFileTool;
import com.forgemind.tools.search.SearchTool;
import com.forgemind.tools.shell.ShellTool;
import java.nio.file.Path;

/**
 * 测试装配：FakeLlmClient + 真实 6 Tool + AgentLoop 完整闭环。
 */
final class AgentHarness {

    private AgentHarness() {
    }

    /** 注册全部 6 个正式 Tool。 */
    static void registerAll(InMemoryToolRegistry registry) {
        registry.register(new ListFilesTool());
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new SearchTool());
        registry.register(new ShellTool());
    }

    /**
     * 构造完整闭环 AgentLoop（no-op ProgressListener）。{@code extraTools} 用于
     * 注入测试专用工具（如 {@link BrokenTool}）。
     */
    static AgentLoop newLoop(Path workspace, FakeLlmClient fake, AgentConfig config,
                             PermissionAnswerer answerer, AgentTool... extraTools) {
        return newLoop(workspace, fake, config, answerer, ProgressListener.NOOP, extraTools);
    }

    /** M8：可注入观察层 ProgressListener（观察 streaming 增量）。 */
    static AgentLoop newLoop(Path workspace, FakeLlmClient fake, AgentConfig config,
                             PermissionAnswerer answerer, ProgressListener progress,
                             AgentTool... extraTools) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registerAll(registry);
        for (AgentTool tool : extraTools) {
            registry.register(tool);
        }
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), answerer, new WorkspaceAccess(workspace));
        return new AgentLoop(workspace, fake, registry, executor, config, progress);
    }
}
