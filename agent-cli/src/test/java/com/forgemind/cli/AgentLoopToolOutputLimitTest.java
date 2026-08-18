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
 * 集成验证：超大 Tool 输出经 ToolResultRenderer 在进入 LLM Context 前被截断，
 * AgentLoop 不崩溃、后续轮次正常完成。
 */
class AgentLoopToolOutputLimitTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("big.txt"),
                "payload ".repeat(300), StandardCharsets.UTF_8);
    }

    @Test
    void oversizedToolOutputIsTruncatedBeforeContext() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "big.txt")))))
                .then(AgentResponse.finalAnswer("done with truncated outputs"));

        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(), 0, 120);
        Agent agent = CliAssembly.buildAgent(config, fake, workspace, req -> false);
        AgentResult result = agent.run("read big file twice");
        assertTrue(result.finished());
        assertEquals("done with truncated outputs", result.finalAnswer());

        // 每轮 TOOL 消息都应被 context 层截断
        for (int round = 1; round <= 2; round++) {
            ChatMessage toolMsg = fake.calls().get(round).stream()
                    .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
            assertTrue(toolMsg.content().contains("[truncated: true]"),
                    "第 " + round + " 轮 TOOL 消息应被标记 truncated");
            assertTrue(toolMsg.content().contains("[output truncated: context output limit]"));
            assertTrue(toolMsg.content().length() < 500,
                    "进入 Context 的文本应被截断，实际长度=" + toolMsg.content().length());
        }
    }
}
