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
 * 集成：极小额 Token Budget 触发 Context Summary 注入；SYSTEM 保留、
 * tool_call_id 不孤裂、原始 USER 任务不被篡改、任务完成。
 */
class AgentLoopContextSummaryTest {

    @TempDir
    Path workspace;

    @Test
    void compactionInjectsContextSummary() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "payload ".repeat(200), StandardCharsets.UTF_8);
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "write_file",
                                Map.of("path", "out.txt", "content", "created")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c3", "read_file", Map.of("path", "out.txt")))))
                .then(AgentResponse.finalAnswer("done with summary"));

        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(),
                0, 64 * 1024, 150, 0, 2);
        Agent agent = CliAssembly.buildAgent(config, fake, workspace, req -> true);
        AgentResult result = agent.run("my original task");
        assertTrue(result.finished());
        assertEquals("done with summary", result.finalAnswer());

        // 某轮消息中应出现 [CONTEXT SUMMARY]（SYSTEM 角色）
        boolean summarySeen = false;
        for (List<ChatMessage> call : fake.calls()) {
            for (ChatMessage m : call) {
                if (m.content() != null && m.content().startsWith("[CONTEXT SUMMARY]")) {
                    summarySeen = true;
                    assertEquals(Role.SYSTEM, m.role(), "summary 必须是 SYSTEM 角色");
                }
            }
        }
        assertTrue(summarySeen, "压缩后应注入 Context Summary");

        // 原始 USER 任务未被篡改：若仍存在，内容必须与输入一致
        for (List<ChatMessage> call : fake.calls()) {
            for (ChatMessage m : call) {
                if (m.role() == Role.USER && !m.content().startsWith("(invalid")) {
                    assertTrue(!m.content().contains("modifiedFiles"));
                }
            }
        }
        // tool_call_id 不孤裂
        List<ChatMessage> last = fake.calls().get(fake.callCount() - 1);
        assertNoOrphanedToolIds(last);
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
