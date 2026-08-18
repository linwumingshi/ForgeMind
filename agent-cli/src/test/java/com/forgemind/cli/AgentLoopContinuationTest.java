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
 * finish_reason=length 自动续写（M7）：
 * 续写上限、length+tool_calls、length+空 content、迭代/工具计数。
 */
class AgentLoopContinuationTest {

    @TempDir
    Path workspace;

    private Agent agent(FakeLlmClient fake) {
        return agent(fake, AgentConfig.defaults());
    }

    private Agent agent(FakeLlmClient fake, AgentConfig config) {
        return CliAssembly.buildAgent(config, fake, workspace, req -> false);
    }

    @Test
    void continuationThenStopCompletes() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("first half", null, "length"))
                .then(AgentResponse.finalAnswer("second half done"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("second half done", result.finalAnswer());
        assertEquals(2, result.iterations());
        assertEquals(0, result.toolCallCount());
    }

    @Test
    void twoContinuationsThenSuccess() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("part 1", null, "length"))
                .then(AgentResponse.withFinishReason("part 2", null, "length"))
                .then(AgentResponse.finalAnswer("final"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("final", result.finalAnswer());
        assertEquals(3, result.iterations());
    }

    @Test
    void exceedingMaxContinuationAttemptsFailsCleanly() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("part 1", null, "length"))
                .then(AgentResponse.withFinishReason("part 2", null, "length"))
                .then(AgentResponse.withFinishReason("part 3", null, "length"));
        // 默认 maxContinuationAttempts=2：第 3 次 length 时超过上限 → 以已截断内容完成
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("part 3", result.finalAnswer());
    }

    @Test
    void zeroContinuationAttemptsDisablesContinuation() {
        AgentConfig config = new AgentConfig(10, com.forgemind.core.config.ToolLimits.defaults(),
                120_000, 64 * 1024, 100_000, 8_000, 0);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("truncated answer", null, "length"));
        AgentResult result = agent(fake, config).run("task");
        assertTrue(result.finished());
        assertEquals("truncated answer", result.finalAnswer(), "禁用续写时按 M6 行为直接完成");
        assertEquals(1, result.iterations());
    }

    @Test
    void lengthWithToolCallsExecutesToolsWithoutContinuation() throws Exception {
        java.nio.file.Files.writeString(workspace.resolve("a.txt"), "hello");
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("thinking", List.of(
                        ToolCall.of("c1", "read_file", Map.of("path", "a.txt"))), "length"))
                .then(AgentResponse.finalAnswer("done"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals(1, result.toolCallCount(), "length+tool_calls 应优先执行工具");
        assertTrue(fake.calls().get(1).stream().anyMatch(m -> m.role() == Role.TOOL));
    }

    @Test
    void lengthWithEmptyContentUsesInvalidMechanism() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason(null, null, "length"))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("recovered", result.finalAnswer());
        // 空 content + length → invalid 反馈（USER），而非 continuation
        ChatMessage last = fake.calls().get(1).get(fake.calls().get(1).size() - 1);
        assertEquals(Role.USER, last.role());
        assertTrue(last.content().contains("invalid response"));
    }

    @Test
    void continuationDoesNotRepeatUserTask() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("first", null, "length"))
                .then(AgentResponse.finalAnswer("second"));
        agent(fake).run("my original task");
        List<ChatMessage> second = fake.calls().get(1);
        // 原始 USER 任务只出现一次；第二条消息是 continuation 提示，不含任务原文重复
        long userMessages = second.stream().filter(m -> m.role() == Role.USER).count();
        assertTrue(userMessages >= 1);
        ChatMessage cont = second.get(second.size() - 1);
        assertFalse(cont.content().contains("my original task"));
    }

    @Test
    void partialAnswerTracksLastContent() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("step A", null, "length"))
                .then(AgentResponse.withFinishReason("step B", null, "length"))
                .then(AgentResponse.withFinishReason("step C", null, "length"));
        AgentResult result = agent(fake).run("task");
        assertEquals("step C", result.finalAnswer());
    }
}
