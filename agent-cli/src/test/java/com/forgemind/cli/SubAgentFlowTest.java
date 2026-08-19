package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.2：主 Agent 装配闭环 —— CliAssembly 注册 sub_agent 工具与
 * DefaultSubAgentFactory；主 Agent → 子 Agent → 结果回灌 → 主 Agent 继续。
 */
class SubAgentFlowTest {

    @TempDir
    Path tempDir;

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    /** 主 loop 与子 loop 共享同一个 FakeLlmClient 脚本（按调用顺序消耗）。 */
    @Test
    void mainAgentSpawnsSubAgentAndContinues() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "count files",
                                "tools", List.of("list_files"))))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("l1", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("found 1 file"))
                .then(AgentResponse.finalAnswer("done: found 1 file"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("count files via subagent");
        assertTrue(result.finished(), "主 Agent 应完成");
        assertEquals("done: found 1 file", result.finalAnswer());

        // 子结果已作为 ToolResult 回灌主 Agent 第二轮
        List<ChatMessage> mainRound2 = fake.calls().get(3);
        ChatMessage toolMsg = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("[subagent:complete]"));
        assertTrue(toolMsg.content().contains("found 1 file"));
        assertTrue(toolMsg.content().contains("iterations=2"));
    }

    @Test
    void subAgentWhitelistRestrictsTools() {
        // 主 Agent 请求 sub_agent 且子 Agent 白名单只含 list_files；
        // 子 Agent 若尝试 read_file（白名单外）→ unknown tool 回灌 → 自纠
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "list only",
                                "tools", List.of("list_files"))))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("r1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("self corrected"))
                .then(AgentResponse.finalAnswer("main done"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        // 子 Agent 第二轮：unknown tool 回灌
        List<ChatMessage> subRound2 = fake.calls().get(2);
        ChatMessage toolMsg = subRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("unknown tool"));
        assertTrue(toolMsg.content().contains("read_file"));
    }

    @Test
    void subAgentFailureFeedsBackToMain() {
        // 子 Agent 预算 1：第二轮触发 max iterations → 子失败 → failure 回灌主 → 主自纠
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of(
                                "task", "do it",
                                "tools", List.of("list_files"),
                                "maxIterations", 1)))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("l1", "list_files", Map.of()))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("l2", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("handled subagent failure"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("handled subagent failure", result.finalAnswer());
        // 主第二轮收到 [subagent:failed]
        List<ChatMessage> mainRound2 = fake.calls().get(3);
        ChatMessage toolMsg = mainRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("[subagent:failed]"));
        assertTrue(toolMsg.content().contains("max iterations"));
    }

    @Test
    void subAgentCannotSpawnNestedSubAgent() {
        // 子 Agent 请求 sub_agent → 白名单/registry 均无 → unknown tool → 自纠
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of("task", "outer")))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s2", "sub_agent", Map.of("task", "inner")))))
                .then(AgentResponse.finalAnswer("inner corrected"))
                .then(AgentResponse.finalAnswer("outer done"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("outer done", result.finalAnswer());
        List<ChatMessage> innerRound2 = fake.calls().get(2);
        ChatMessage toolMsg = innerRound2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("unknown tool"));
        assertTrue(toolMsg.content().contains("sub_agent"));
    }

    @Test
    void maxSubAgentsLimitEnforced() {
        AgentConfig config = new AgentConfig(30, com.forgemind.core.config.ToolLimits.defaults(),
                120_000, 64L * 1024, 100_000, 8_000, 2, 1); // maxSubAgents=1
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of("task", "first")))))
                .then(AgentResponse.finalAnswer("first ok"))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s2", "sub_agent", Map.of("task", "second")))))
                .then(AgentResponse.finalAnswer("main final"));

        Agent agent = CliAssembly.buildAgent(config, fake, tempDir, req -> true);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("main final", result.finalAnswer());
        // 第二次 sub_agent 调用 → limit exceeded 失败回灌 → 主自纠
        List<ChatMessage> round = fake.calls().get(3);
        ChatMessage toolMsg = round.stream()
                .filter(m -> m.role() == Role.TOOL && m.content() != null
                        && m.content().contains("limit exceeded"))
                .findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("limit exceeded"),
                "TOOL 消息应为 limit exceeded，实际: " + toolMsg.content());
    }

    @Test
    void subAgentContextIsolation() {
        // 子 Agent 的 system/user 消息不得出现在主 Agent 的调用历史中
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("s1", "sub_agent", Map.of("task", "sub-secret-task")))))
                .then(AgentResponse.withToolCalls(null, List.of(
                        call("l1", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("inner done"))
                .then(AgentResponse.finalAnswer("main done"));

        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> true);
        AgentResult result = agent.run("main-task");
        assertTrue(result.finished());
        // 主 Agent 第一轮（index 0）：只有 system + user(main-task)
        List<ChatMessage> mainRound1 = fake.calls().get(0);
        assertTrue(mainRound1.stream().anyMatch(m -> m.role() == Role.USER
                && "main-task".equals(m.content())));
        assertFalse(mainRound1.stream().anyMatch(m -> m.content() != null
                && m.content().contains("sub-secret-task")),
                "子任务描述不得进入主 Context");
        // 主 Agent 第二轮（index 3）：TOOL 消息只含子结果摘要，不含子内部消息
        List<ChatMessage> mainRound2 = fake.calls().get(3);
        assertFalse(mainRound2.stream().anyMatch(m -> m.role() == Role.USER
                && m.content() != null && m.content().contains("sub-secret-task")));
    }
}
