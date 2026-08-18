package com.forgemind.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.testutil.EchoTool;
import com.forgemind.core.testutil.StubLlmClient;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void delegatesTaskToAgentLoop() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), req -> true, new WorkspaceAccess(tempDir));
        AgentLoop loop = new AgentLoop(tempDir, new StubLlmClient(AgentResponse.finalAnswer("ok")),
                registry, executor, AgentConfig.defaults());

        AgentResult result = new DefaultAgent(loop).run("hello");
        assertTrue(result.finished());
        assertEquals("ok", result.finalAnswer());
    }
}
