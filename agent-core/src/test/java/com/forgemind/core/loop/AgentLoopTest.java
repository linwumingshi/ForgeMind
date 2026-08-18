package com.forgemind.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.testutil.EchoTool;
import com.forgemind.core.testutil.FailingLlmClient;
import com.forgemind.core.testutil.StubLlmClient;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentLoop 核心循环测试（使用 StubLlmClient 脚本化响应）。
 */
class AgentLoopTest {

    @TempDir
    Path tempDir;

    private InMemoryToolRegistry registry;
    private DefaultToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        registry.register(new EchoTool());
        executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), req -> true, new WorkspaceAccess(tempDir));
    }

    private AgentLoop newLoop(StubLlmClient stub, AgentConfig config) {
        return new AgentLoop(tempDir, stub, registry, executor, config);
    }

    @Test
    void returnsFinalAnswerWithoutTools() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, AgentConfig.defaults()).run("analyze");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
        assertEquals(1, result.iterations());
        assertEquals(0, result.toolCallCount());

        List<ChatMessage> first = stub.calls().get(0);
        assertEquals(Role.SYSTEM, first.get(0).role());
        assertEquals(Role.USER, first.get(1).role());
        assertTrue(first.get(0).content().contains("coding agent"));
    }

    @Test
    void executesToolCallAndFeedsResultBack() {
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("call-1", "echo", Map.of("text", "hi")))),
                AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, AgentConfig.defaults()).run("task");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolCallCount());

        List<ChatMessage> second = stub.calls().get(1);
        ChatMessage toolMsg = second.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("call-1", toolMsg.toolCallId(), "Tool Result 必须关联正确的 Tool Call ID");
        // M3 渲染格式：元数据行 + 正文，信息完整保留
        assertTrue(toolMsg.content().contains("[tool: echo]"));
        assertTrue(toolMsg.content().contains("[success: true]"));
        assertTrue(toolMsg.content().contains("[exitCode: null]"));
        assertTrue(toolMsg.content().contains("[truncated: false]"));
        assertTrue(toolMsg.content().contains("echo: hi"));
        assertTrue(second.stream().anyMatch(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null));
    }

    @Test
    void stopsWhenMaxIterationsExceeded() {
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "echo", Map.of("text", "a")))),
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "echo", Map.of("text", "b")))));
        AgentResult result = newLoop(stub, new AgentConfig(2)).run("task");
        assertFalse(result.finished());
        assertTrue(result.error().contains("max iterations"));
        assertEquals(2, result.iterations());
        assertEquals(2, result.toolCallCount());
    }

    @Test
    void wrapsLlmFailureIntoFailedResult() {
        AgentLoop loop = new AgentLoop(tempDir, new FailingLlmClient(), registry, executor,
                AgentConfig.defaults());
        AgentResult result = loop.run("task");
        assertFalse(result.finished());
        assertTrue(result.error().contains("api down"));
    }

    @Test
    void systemPromptListsRegisteredTools() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("ok"));
        newLoop(stub, AgentConfig.defaults()).run("task");
        String system = stub.calls().get(0).get(0).content();
        assertTrue(system.contains("echo"));
        assertTrue(system.contains(tempDir.toString()));
    }
}
