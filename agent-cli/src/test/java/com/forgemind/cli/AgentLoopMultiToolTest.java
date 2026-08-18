package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import com.forgemind.llm.fake.FakeLlmClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 闭环多轮测试：工具链、中间思考内容、消息序列增长。
 */
class AgentLoopMultiToolTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello a");
    }

    @Test
    void threeRoundToolChainReadWriteThenFinal() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "write_file", Map.of("path", "b.txt", "content", "hello b")))))
                .then(AgentResponse.finalAnswer("chain complete"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> true)
                .run("copy a to b");
        assertTrue(result.finished());
        assertEquals("chain complete", result.finalAnswer());
        assertEquals(3, result.iterations());
        assertEquals(2, result.toolCallCount());
        assertTrue(Files.exists(workspace.resolve("b.txt")), "write_file 应真实执行");
    }

    @Test
    void assistantContentBetweenToolCallsIsPreserved() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("I will read the file first",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("done"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false).run("t");

        List<ChatMessage> second = fake.calls().get(1);
        ChatMessage assistant = second.stream()
                .filter(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null)
                .findFirst().orElseThrow();
        assertEquals("I will read the file first", assistant.content());
    }

    @Test
    void messageSequenceGrowsAcrossRounds() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "search", Map.of("query", "hello")))))
                .then(AgentResponse.finalAnswer("end"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false).run("t");

        assertEquals(3, fake.callCount());
        int size1 = fake.calls().get(0).size();
        int size2 = fake.calls().get(1).size();
        int size3 = fake.calls().get(2).size();
        assertTrue(size2 > size1, "第二轮消息应比第一轮多（assistant tool_calls + tool 结果）");
        assertTrue(size3 > size2, "第三轮消息应比第二轮多");
    }
}
