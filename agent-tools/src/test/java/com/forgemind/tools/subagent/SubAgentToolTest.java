package com.forgemind.tools.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.context.ToolContext;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.core.subagent.SubAgentFactory;
import com.forgemind.core.subagent.SubAgentSpec;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.2：SubAgentTool —— 参数校验、maxSubAgents 限制、渲染、ProgressListener 回调。
 */
class SubAgentToolTest {

    @TempDir
    Path tempDir;

    private ToolContext ctx() {
        return new ToolContext(new WorkspaceAccess(tempDir), ToolLimits.defaults());
    }

    /** 记录被请求 spec 的桩工厂。 */
    private static final class StubFactory implements SubAgentFactory {
        final int max;
        final AgentResult result;
        SubAgentSpec lastSpec;

        StubFactory(int max, AgentResult result) {
            this.max = max;
            this.result = result;
        }

        @Override
        public AgentResult run(SubAgentSpec spec) {
            lastSpec = spec;
            return result;
        }

        @Override
        public int maxSubAgents() {
            return max;
        }
    }

    @Test
    void executesWithFullSpec() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("sub answer", 2, 1));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of(
                "task", "analyze",
                "tools", List.of("read_file"),
                "maxIterations", 10));
        assertTrue(result.success());
        assertTrue(result.output().contains("[subagent:complete]"));
        assertTrue(result.output().contains("sub answer"));
        assertTrue(result.output().contains("iterations=2"));
        assertTrue(result.output().contains("toolCalls=1"));
        assertEquals("analyze", factory.lastSpec.task());
        assertEquals(List.of("read_file"), factory.lastSpec.tools());
        assertEquals(10, factory.lastSpec.maxIterations());
    }

    @Test
    void inheritsToolsWhenOmitted() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of("task", "t"));
        assertTrue(result.success());
        assertTrue(factory.lastSpec.inheritsAllTools());
        assertEquals(null, factory.lastSpec.maxIterations());
    }

    @Test
    void missingTaskRejected() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("task"));
        assertEquals(0, factory.lastSpec == null ? 0 : 1, "校验失败不应调用工厂");
    }

    @Test
    void nonStringToolsRejected() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of("task", "t", "tools", List.of(42)));
        assertFalse(result.success());
    }

    @Test
    void nonPositiveMaxIterationsRejected() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of("task", "t", "maxIterations", 0));
        assertFalse(result.success());
        assertTrue(result.error().contains("maxIterations"));
    }

    @Test
    void maxSubAgentsLimitReturnsFailure() {
        StubFactory factory = new StubFactory(2, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        assertTrue(tool.execute(ctx(), Map.of("task", "a")).success());
        assertTrue(tool.execute(ctx(), Map.of("task", "b")).success());
        ToolResult third = tool.execute(ctx(), Map.of("task", "c"));
        assertFalse(third.success());
        assertTrue(third.error().contains("limit exceeded"));
    }

    @Test
    void failedSubAgentRenderedAsFailure() {
        StubFactory factory = new StubFactory(5,
                AgentResult.failed("partial", 3, 2, "boom"));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        ToolResult result = tool.execute(ctx(), Map.of("task", "t"));
        assertFalse(result.success());
        assertTrue(result.error().contains("[subagent:failed]"));
        assertTrue(result.error().contains("boom"));
        assertTrue(result.error().contains("iterations=3"));
    }

    @Test
    void progressListenerReceivesLifecycleEvents() {
        List<String> started = new ArrayList<>();
        List<String[]> results = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onSubAgentStarted(String task) {
                started.add(task);
            }

            @Override
            public void onSubAgentResult(String task, boolean success) {
                results.add(new String[]{task, String.valueOf(success)});
            }
        };
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, listener);
        tool.execute(ctx(), Map.of("task", "my-sub"));
        assertEquals(List.of("my-sub"), started);
        assertEquals(1, results.size());
        assertEquals("my-sub", results.get(0)[0]);
        assertEquals("true", results.get(0)[1]);
    }

    @Test
    void toolNameIsSubAgentAndScopeIsRead() {
        StubFactory factory = new StubFactory(5, AgentResult.completed("ok", 1, 0));
        SubAgentTool tool = new SubAgentTool(factory, ProgressListener.NOOP);
        assertEquals("sub_agent", tool.name());
        assertEquals(com.forgemind.core.permission.PermissionScope.READ, tool.permissionScope());
    }
}
