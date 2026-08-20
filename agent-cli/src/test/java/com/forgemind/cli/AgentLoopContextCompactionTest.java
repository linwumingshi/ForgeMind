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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 集成验证：AgentLoop 真正调用 ContextCompactor（极小 contextMaxChars + 多轮
 * 大 Tool 输出），system 保留、最近消息保留、tool_call_id 不孤裂、任务完成。
 */
class AgentLoopContextCompactionTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        // 大文件：每次 read 产生远超预算的 TOOL 消息
        Files.writeString(workspace.resolve("big.txt"),
                "payload ".repeat(300), StandardCharsets.UTF_8);
    }

    @Test
    void compactionIsTriggeredByAgentLoop() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c3", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c4", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.finalAnswer("done"));

        // M7：token 预算优先；此处显式禁用 token（=0）以验证 M6 字符预算路径。
        // 2000：system prompt（含 P0-2 环境块 ~411 字符）+ summary + user 之上留足余量，
        // 使压缩"删最旧中间组"后保留末尾工具组（而非删光）。
        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(),
                2000, 64 * 1024, 0, 0, 2);
        Agent agent = CliAssembly.buildAgent(config, fake, workspace, req -> false);
        AgentResult result = agent.run("read repeatedly");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());

        // 最后一轮消息：若未压缩应为 2 + 4*2 = 10 条；压缩后应明显更少，且 ≥ 2
        List<ChatMessage> lastCall = fake.calls().get(fake.callCount() - 1);
        assertTrue(lastCall.size() < 10, "AgentLoop 应触发压缩，实际消息数=" + lastCall.size());
        assertTrue(lastCall.size() >= 2);

        // system 永远保留
        assertEquals(Role.SYSTEM, lastCall.get(0).role());
        // 最近消息保留：末尾可能为 TOOL/ASSISTANT（工具组），或 SYSTEM（P0-2 环境块使
        // system 变大后压缩触发 ContextSummary 注入在末尾）——两者均合法。
        Role last = lastCall.get(lastCall.size() - 1).role();
        assertTrue(last == Role.TOOL || last == Role.ASSISTANT || last == Role.SYSTEM,
                "末尾角色异常: " + last);
        // tool_call_id 不孤裂
        assertNoOrphanedToolIds(lastCall);
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
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                boolean hasTool = i + 1 < messages.size() && messages.get(i + 1).role() == Role.TOOL;
                assertTrue(hasTool, "ASSISTANT(tool_calls) 缺少对应 TOOL");
            }
        }
    }
}
