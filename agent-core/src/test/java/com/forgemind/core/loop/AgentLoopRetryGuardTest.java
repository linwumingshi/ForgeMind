package com.forgemind.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.testutil.ControlledFailureTool;
import com.forgemind.core.testutil.StubLlmClient;
import com.forgemind.core.tool.DefaultToolExecutor;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.AgentResponse;
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
 * AgentLoop 重复失败护栏集成测试：验证相同 shell 命令连续失败时提示注入时机与强度。
 *
 * <p>提示是追加进上下文的 user 消息，会保留在后续历史里，因此用
 * {@code countUserMessages} 计数断言"新增了几条提示"，避免历史残留误判。</p>
 */
class AgentLoopRetryGuardTest {

    private static final String CMD_A = "java demo.OrderDemo";
    private static final String CMD_B = "javac -d . demo/OrderDemo.java";

    @TempDir
    Path tempDir;

    private ControlledFailureTool shellTool;
    private InMemoryToolRegistry registry;

    @BeforeEach
    void setUp() {
        shellTool = new ControlledFailureTool();
        registry = new InMemoryToolRegistry();
        registry.register(shellTool);
    }

    private StubLlmClient runAgent(AgentResponse... script) {
        StubLlmClient stub = new StubLlmClient(script);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry,
                PolicyPermissionManager.withDefaults(), req -> true, new WorkspaceAccess(tempDir));
        AgentLoop loop = new AgentLoop(tempDir, stub, registry, executor, AgentConfig.defaults());
        loop.run("task");
        return stub;
    }

    private static AgentResponse shellCall(String command) {
        return AgentResponse.withToolCalls(null,
                List.of(ToolCall.of("c-" + command.hashCode(), "shell", Map.of("command", command))));
    }

    private static int countUserMessages(List<ChatMessage> messages, String keyword) {
        return (int) messages.stream()
                .filter(m -> m.role() == Role.USER && m.content() != null && m.content().contains(keyword))
                .count();
    }

    @Test
    void firstFailureDoesNotInjectHint() {
        StubLlmClient stub = runAgent(shellCall(CMD_A), AgentResponse.finalAnswer("done"));
        List<ChatMessage> finalCall = stub.calls().get(stub.calls().size() - 1);
        assertEquals(0, countUserMessages(finalCall, "has failed repeatedly"));
    }

    @Test
    void secondConsecutiveFailureInjectsWeakHint() {
        StubLlmClient stub = runAgent(shellCall(CMD_A), shellCall(CMD_A), AgentResponse.finalAnswer("done"));
        List<ChatMessage> finalCall = stub.calls().get(stub.calls().size() - 1);
        // 仅第 2 次失败注入 1 条弱提示
        assertEquals(1, countUserMessages(finalCall, "has failed repeatedly"));
        assertEquals(0, countUserMessages(finalCall, "Do not retry it again"));
    }

    @Test
    void thirdConsecutiveFailureInjectsStrongHint() {
        StubLlmClient stub = runAgent(shellCall(CMD_A), shellCall(CMD_A), shellCall(CMD_A),
                AgentResponse.finalAnswer("done"));
        List<ChatMessage> finalCall = stub.calls().get(stub.calls().size() - 1);
        // 第 2 次弱提示 + 第 3 次强提示
        assertEquals(1, countUserMessages(finalCall, "has failed repeatedly"));
        assertEquals(1, countUserMessages(finalCall, "Do not retry it again"));
    }

    @Test
    void differentCommandsDoNotShareCounter() {
        // A 失败两次（触发弱提示），随后 B 失败一次：B 不应新增提示
        StubLlmClient stub = runAgent(shellCall(CMD_A), shellCall(CMD_A), shellCall(CMD_B),
                AgentResponse.finalAnswer("done"));
        List<ChatMessage> finalCall = stub.calls().get(stub.calls().size() - 1);
        assertEquals(1, countUserMessages(finalCall, "has failed repeatedly"),
                "只有命令 A 的弱提示，命令 B 第一次失败不应新增提示");
    }

    @Test
    void successResetsConsecutiveCounter() {
        // 前 2 次失败（触发弱提示），第 3 次成功清零，第 4 次失败回到第 1 次 → 不新增提示
        shellTool.failNextTimes(2);
        StubLlmClient stub = runAgent(shellCall(CMD_A), shellCall(CMD_A), shellCall(CMD_A),
                shellCall(CMD_A), AgentResponse.finalAnswer("done"));
        List<ChatMessage> finalCall = stub.calls().get(stub.calls().size() - 1);
        assertEquals(1, countUserMessages(finalCall, "has failed repeatedly"),
                "成功后重新失败不新增提示（仅保留此前那条）");
    }

    @Test
    void hintInjectedBeforeNextLlmCall() {
        // 提示必须出现在下一次 LLM 调用的上下文中（而非当轮之后）
        StubLlmClient stub = runAgent(shellCall(CMD_A), shellCall(CMD_A), AgentResponse.finalAnswer("done"));
        // 第二次 shell 失败的下一轮（即第 3 次 LLM 调用）应能看到弱提示
        List<ChatMessage> thirdCall = stub.calls().get(2);
        assertTrue(thirdCall.stream().anyMatch(m -> m.role() == Role.USER
                && m.content() != null && m.content().contains("has failed repeatedly")));
    }
}
