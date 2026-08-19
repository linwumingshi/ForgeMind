package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回归：任务已完成但接近 maxIterations（黑盒场景）。
 *
 * <p>场景：Agent 完成核心工作（读→改→编译→运行→验证）后，LLM 仍请求清理类
 * 工具调用；预算将尽时应注入收尾提示，让 LLM 在最后 1-2 轮收敛到 final answer，
 * 而不是"清理工具耗尽预算 → 来不及输出最终答案 → 误报 failed"。</p>
 */
class AgentLoopCompletionTest {

    @TempDir
    Path workspace;

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    /**
     * 任务核心已完成，仅剩清理工具 + 最终答案：预算 5 轮内应正常 completed，
     * 即使清理类工具调用占用轮次。
     */
    @Test
    void completesWhenCleanupEatsBudgetButFinalAnswerArrivesInTime() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("reading", List.of(call("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls("fixing", List.of(call("c2", "write_file",
                        Map.of("path", "b.txt", "content", "fixed")))))
                .then(AgentResponse.withToolCalls("building", List.of(call("c3", "shell", Map.of("command", "echo ok")))))
                .then(AgentResponse.withToolCalls("cleanup", List.of(call("c4", "shell", Map.of("command", "echo clean")))))
                .then(AgentResponse.finalAnswer("bug fixed and verified"));
        // 预算 5：4 轮工具 + 第 5 轮 final answer
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, new AgentConfig(5), req -> true);
        AgentResult result = loop.run("fix the bug");
        assertTrue(result.finished(), "任务完成但接近预算上限时应正常结束，而非 failed: " + result.error());
        assertEquals("bug fixed and verified", result.finalAnswer());
        assertEquals(5, result.iterations());
        assertEquals(4, result.toolCallCount());
    }

    /**
     * 预算更紧（4）：清理工具占 3 轮 + 收尾 1 轮，仍应 completed。
     * 关键：不得因清理工具消耗而错过 final answer。
     */
    @Test
    void completesWithTighterBudgetWhenFinalAnswerArrivesOnLastIteration() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(call("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls(null, List.of(call("c2", "shell", Map.of("command", "echo build")))))
                .then(AgentResponse.withToolCalls(null, List.of(call("c3", "shell", Map.of("command", "echo clean")))))
                .then(AgentResponse.finalAnswer("done in time"));
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, new AgentConfig(4), req -> true);
        AgentResult result = loop.run("task");
        assertTrue(result.finished(), "最后一轮给出 final answer 应完成: " + result.error());
        assertEquals("done in time", result.finalAnswer());
        assertEquals(4, result.iterations());
    }

    /** 收尾提示确实被注入：最后 1-2 轮的 LLM 上下文含预算提示，促使 LLM 收尾。 */
    @Test
    void budgetHintIsInjectedBeforeLastIterations() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(call("c1", "shell", Map.of("command", "echo a")))))
                .then(AgentResponse.withToolCalls(null, List.of(call("c2", "shell", Map.of("command", "echo b")))))
                .then(AgentResponse.withToolCalls(null, List.of(call("c3", "shell", Map.of("command", "echo c")))))
                .then(AgentResponse.finalAnswer("wrapped up"));
        // maxIterations=5：iterations>=4 时注入 → 第 4 轮（倒数第 2 次调用）上下文含提示
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, new AgentConfig(5), req -> true);
        AgentResult result = loop.run("task");
        assertTrue(result.finished());
        // 第 4 轮调用（索引 3）上下文应包含收尾提示
        List<ChatMessage> round4 = fake.calls().get(3);
        assertTrue(round4.stream().anyMatch(m -> m.role() == Role.USER
                        && m.content() != null && m.content().contains("iteration budget")),
                "预算将尽时应注入收尾提示，促 LLM 输出 final answer");
        // 第 1 轮（索引 0）不应含提示
        assertFalse(fake.calls().get(0).stream().anyMatch(m -> m.role() == Role.USER
                        && m.content() != null && m.content().contains("iteration budget")),
                "预算充足时不应提前注入提示");
    }

    /** 若 LLM 无视提示持续调用工具直至耗尽 → 仍正确 failed（预算硬边界不变）。 */
    @Test
    void stillFailsWhenToolsConsumeEntireBudget() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(call("c1", "shell", Map.of("command", "echo 1")))))
                .then(AgentResponse.withToolCalls(null, List.of(call("c2", "shell", Map.of("command", "echo 2")))));
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, new AgentConfig(2), req -> true);
        AgentResult result = loop.run("task");
        assertFalse(result.finished(), "预算耗尽且无 final answer 仍应 failed");
        assertTrue(result.error().contains("max iterations"));
    }

    /** maxIterations=1 时提示注入不应破坏预算语义（提示在第 1 轮即注入，仍可能耗尽）。 */
    @Test
    void budgetHintDoesNotChangeTinyBudgetSemantics() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(call("c1", "shell", Map.of("command", "echo x")))));
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, new AgentConfig(1), req -> true);
        AgentResult result = loop.run("task");
        assertFalse(result.finished());
        assertTrue(result.error().contains("max iterations"));
    }
}
