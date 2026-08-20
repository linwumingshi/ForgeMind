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

    @Test
    void systemPromptContainsEnvironmentBlock() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("ok"));
        newLoop(stub, AgentConfig.defaults()).run("task");
        String system = stub.calls().get(0).get(0).content();
        // 环境块：OS / Shell / 工作目录（本机 Windows + 默认 CMD）
        assertTrue(system.contains("Environment:"));
        assertTrue(system.contains("Windows"));
        assertTrue(system.contains("cmd.exe"));
        assertTrue(system.contains(tempDir.toAbsolutePath().normalize().toString()));
        // 失败诊断规则与防重复规则
        assertTrue(system.contains("inspect the returned stderr/output"));
        assertTrue(system.contains("Do not blindly repeat the same or equivalent command."));
    }

    private static AgentResponse echoCall(String id) {
        return AgentResponse.withToolCalls(null,
                List.of(ToolCall.of(id, "echo", Map.of("text", "hi"))));
    }

    private static int countUserMessages(List<ChatMessage> messages, String keyword) {
        return (int) messages.stream()
                .filter(m -> m.role() == Role.USER && m.content() != null && m.content().contains(keyword))
                .count();
    }

    @Test
    void budgetHintNotInjectedWithSixRemaining() {
        // maxIterations=7：第 1 轮剩余 6 轮未到阈值（≤5），不注入；第 2 轮剩余 5 轮才触发
        StubLlmClient stub = new StubLlmClient(
                echoCall("c1"), echoCall("c2"), AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, new AgentConfig(7)).run("task");
        assertTrue(result.finished());
        // 第 1 次 LLM 调用：第 1 轮 remaining=6，无 hint
        assertEquals(0, countUserMessages(stub.calls().get(0), "Iteration budget is nearly exhausted"));
        // 第 2 次 LLM 调用：第 2 轮 remaining=5，已注入
        assertEquals(1, countUserMessages(stub.calls().get(1), "Iteration budget is nearly exhausted"));
    }

    @Test
    void budgetHintInjectedWithFiveRemaining() {
        // maxIterations=6：第 1 轮剩余 5 轮，触发收尾提示
        StubLlmClient stub = new StubLlmClient(echoCall("c1"), AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, new AgentConfig(6)).run("task");
        assertTrue(result.finished());
        List<ChatMessage> second = stub.calls().get(1);
        assertEquals(1, countUserMessages(second, "Iteration budget is nearly exhausted"));
    }

    @Test
    void budgetHintInjectedWhenBudgetSmall() {
        // maxIterations=2：第 1 轮剩余 1 轮，小预算也要尽早收尾
        StubLlmClient stub = new StubLlmClient(echoCall("c1"), AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, new AgentConfig(2)).run("task");
        assertTrue(result.finished());
        List<ChatMessage> second = stub.calls().get(1);
        assertEquals(1, countUserMessages(second, "Iteration budget is nearly exhausted"));
    }

    @Test
    void budgetHintInjectedOnlyOnce() {
        // 只注入一次，避免每轮重复增加 token
        StubLlmClient stub = new StubLlmClient(
                echoCall("c1"), echoCall("c2"), echoCall("c3"), AgentResponse.finalAnswer("done"));
        AgentResult result = newLoop(stub, new AgentConfig(6)).run("task");
        assertTrue(result.finished());
        List<ChatMessage> last = stub.calls().get(stub.calls().size() - 1);
        assertEquals(1, countUserMessages(last, "Iteration budget is nearly exhausted"));
    }
}
