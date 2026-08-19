package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.ProgressListener;
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
 * M9.3：SubAgent 隔离/失败/取消语义强化。
 * 覆盖：取消传导（主/子均 cancelled）、子内工具失败自纠、
 * 预算耗尽不误判 cancelled、主 Context 只收子结果摘要（tool_call_id 隔离）。
 */
class SubAgentIsolationTest {

    @TempDir
    Path tempDir;

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    /**
     * 任务级取消：onSubAgentStarted 触发 interrupt → 子 Agent 首轮边界即 cancelled
     * （不执行任何工具、不再调 LLM），主 Agent 下一轮边界同样 cancelled。
     */
    @Test
    void interruptCancelsMainAndSubAgent() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of("task", "sub")))))
                .then(AgentResponse.finalAnswer("never consumed"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir,
                req -> true, new ProgressListener() {
                    @Override
                    public void onSubAgentStarted(String task) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            AgentResult result = agent.run("task");
            assertFalse(result.finished());
            assertTrue(result.error().contains("cancelled"), "主 Agent 应取消: " + result.error());
            // 子 Agent 未执行任何工具、未继续下一轮；主 Agent 也未继续
            assertEquals(1, fake.callCount(), "中断后主/子均不得再调用 LLM");
        } finally {
            Thread.interrupted(); // 清理中断标志
        }
    }

    /**
     * 子 Agent 内部工具失败（read_file 不存在）→ 子 Agent 读到失败 ToolResult →
     * 自纠完成 → 子结果回灌主 → 主继续完成。普通子失败不传播成 cancelled。
     */
    @Test
    void subAgentToolFailureSelfCorrectsThenMainContinues() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "read missing",
                                "tools", List.of("read_file"))))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("r1", "read_file", Map.of("path", "missing.txt")))))
                .then(AgentResponse.finalAnswer("adjusted inside sub"))
                .then(AgentResponse.finalAnswer("main done"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished(), "子内失败不应导致主任务失败: " + result.error());
        assertEquals("main done", result.finalAnswer());
        // 子第二轮收到 read_file 失败回灌
        List<ChatMessage> subRound2 = fake.calls().get(2);
        ChatMessage toolMsg = subRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("[tool: read_file]"));
        assertTrue(toolMsg.content().contains("ERROR"));
        // 主第二轮收到 [subagent:complete]（子自纠完成），不是 cancelled
        List<ChatMessage> mainRound2 = fake.calls().get(3);
        ChatMessage mainTool = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(mainTool.content().contains("[subagent:complete]"));
        assertFalse(mainTool.content().contains("cancelled"));
    }

    /** 子预算耗尽 → failure 回灌主 → 主继续自纠；不得误判成整个任务 cancelled。 */
    @Test
    void subBudgetExhaustionNotMisclassifiedAsCancelled() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "limited",
                                "tools", List.of("list_files"),
                                "maxIterations", 1)))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("l1", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("main recovered"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished(), "子预算耗尽不应取消主任务: " + result.error());
        assertEquals("main recovered", result.finalAnswer());
        // 主第二轮 TOOL：子失败标记为 [subagent:failed]，而非 cancelled
        List<ChatMessage> mainRound2 = fake.calls().get(2);
        ChatMessage mainTool = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(mainTool.content().contains("[subagent:failed]"));
        assertFalse(mainTool.content().contains("cancelled"),
                "预算耗尽（failure）不得被渲染成任务取消");
    }

    /**
     * Context / tool_call_id 隔离：主 Context 只收到 sub_agent 的 ToolResult 摘要，
     * 不带子 Agent 内部的 tool_call_id；子内部 read_file 原始输出不进入主 Context。
     */
    @Test
    void mainContextOnlySeesSubAgentSummaryWithOwnToolCallId() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "secret-content", StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "read file",
                                "tools", List.of("read_file"))))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("r1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("inner done"))
                .then(AgentResponse.finalAnswer("main done"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("main done", result.finalAnswer());

        // 主第二轮（index 3）只含 sub_agent 的 TOOL 结果
        List<ChatMessage> mainRound2 = fake.calls().get(3);
        ChatMessage mainTool = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("s1", mainTool.toolCallId(), "主 Context 的 TOOL 消息必须关联 sub_agent 调用");
        assertTrue(mainTool.content().contains("[subagent:complete]"));
        // 子内部 read_file 的 tool_call_id 与原始内容不得泄漏到主 Context
        assertFalse(mainRound2.stream().anyMatch(m -> "r1".equals(m.toolCallId())),
                "子内部 tool_call_id 不得进入主 Context");
        assertFalse(mainTool.content().contains("secret-content"),
                "子内部工具原始输出不得进入主 Context");
        // 子内部消息只存在于子 loop 的调用中（index 1/2），不污染主历史
        assertFalse(mainRound2.stream().anyMatch(m -> m.role() == Role.USER
                && m.content() != null && m.content().contains("read file")),
                "子任务描述不得进入主 Context");
    }

    /** 非法工具白名单 → 创建时硬拒绝 → failure 回灌主 → 主继续（不崩溃、不取消）。 */
    @Test
    void invalidWhitelistFeedsBackAndMainContinues() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "bad tools",
                                "tools", List.of("nope", "also_missing"))))))
                .then(AgentResponse.finalAnswer("main recovered from rejected whitelist"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished(), "非法白名单不应导致主任务异常: " + result.error());
        assertEquals("main recovered from rejected whitelist", result.finalAnswer());
        // 主第二轮 TOOL：subagent rejected（含被拒工具名）
        List<ChatMessage> mainRound2 = fake.calls().get(1);
        ChatMessage mainTool = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(mainTool.content().contains("not available"));
        assertTrue(mainTool.content().contains("nope"));
    }

    /** 多子 Agent 共用全局 maxSubAgents 预算：max=2 时第三个被拒绝。 */
    @Test
    void multipleSubAgentsShareGlobalBudget() {
        AgentConfig config = new AgentConfig(30, com.forgemind.core.config.ToolLimits.defaults(),
                120_000, 64L * 1024, 100_000, 8_000, 2, 2); // maxSubAgents=2
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of("task", "one")))))
                .then(AgentResponse.finalAnswer("one ok"))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s2", "sub_agent", Map.of("task", "two")))))
                .then(AgentResponse.finalAnswer("two ok"))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s3", "sub_agent", Map.of("task", "three")))))
                .then(AgentResponse.finalAnswer("main final"));
        Agent agent = CliAssembly.buildAgent(config, fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("main final", result.finalAnswer());
        // 主第二轮（index 5）TOOL 含 limit exceeded（第三个被拒）
        List<ChatMessage> round = fake.calls().get(5);
        ChatMessage toolMsg = round.stream()
                .filter(m -> m.role() == Role.TOOL && m.content() != null
                        && m.content().contains("limit exceeded"))
                .findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("limit exceeded"));
    }
}
