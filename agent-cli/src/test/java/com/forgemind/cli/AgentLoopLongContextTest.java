package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.ToolLimits;
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
 * 集成：Token Budget（contextMaxTokens）在 AgentLoop 中真实触发压缩；
 * 回退语义：contextMaxTokens=0 时仍可用 M6 字符预算。
 */
class AgentLoopLongContextTest {

    @TempDir
    Path workspace;

    @Test
    void tokenBudgetTriggersCompaction() throws Exception {
        Files.writeString(workspace.resolve("big.txt"), "payload ".repeat(300), StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c3", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c4", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.finalAnswer("token budget done"));

        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(),
                0, 64 * 1024, 80, 0, 2);
        Agent agent = CliAssembly.buildAgent(config, fake, workspace, req -> false);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("token budget done", result.finalAnswer());

        List<ChatMessage> last = fake.calls().get(fake.callCount() - 1);
        // 未压缩时 2 + 4*2 = 10 条；token 压缩后应更少
        assertTrue(last.size() < 10, "Token Budget 应触发压缩，实际=" + last.size());
        assertEquals(Role.SYSTEM, last.get(0).role());
        assertNoOrphanedToolIds(last);
    }

    @Test
    void zeroTokenBudgetFallsBackToChars() throws Exception {
        Files.writeString(workspace.resolve("big.txt"), "payload ".repeat(300), StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.finalAnswer("chars budget done"));
        // contextMaxTokens=0（禁用 token）→ 回退 M6 字符预算
        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(),
                100, 64 * 1024, 0, 0, 2);
        Agent agent = CliAssembly.buildAgent(config, fake, workspace, req -> false);
        AgentResult result = agent.run("task");
        assertTrue(result.finished());
        assertEquals("chars budget done", result.finalAnswer());
        List<ChatMessage> last = fake.calls().get(fake.callCount() - 1);
        assertTrue(last.size() < 2 + 2 * 2, "字符预算应触发压缩");
    }

    private static void assertNoOrphanedToolIds(List<ChatMessage> messages) {
        boolean expectingTools = false;
        for (ChatMessage m : messages) {
            if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                expectingTools = true;
            } else if (m.role() == Role.TOOL) {
                assertTrue(expectingTools, "孤立 TOOL：tool_call_id=" + m.toolCallId());
            } else {
                expectingTools = false;
            }
        }
    }
}
