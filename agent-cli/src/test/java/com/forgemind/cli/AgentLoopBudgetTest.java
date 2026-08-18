package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ToolCall;
import com.forgemind.llm.fake.FakeLlmClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 闭环预算测试：maxIterations 耗尽、部分成功后的结果保留、不裸抛异常。
 */
class AgentLoopBudgetTest {

    @TempDir
    Path workspace;

    @Test
    void maxIterationsExceededWithToolCalls() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "b.txt")))));
        AgentResult result = AgentHarness.newLoop(workspace, fake, new AgentConfig(2), req -> false)
                .run("t");
        assertFalse(result.finished());
        assertNotNull(result.error());
        assertTrue(result.error().contains("max iterations"));
        assertEquals(2, result.iterations());
        assertEquals(2, result.toolCallCount());
    }

    @Test
    void partialAnswerPreservedWhenBudgetExhausted() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("step 1 thinking",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.withToolCalls("step 2 thinking",
                        List.of(ToolCall.of("c2", "read_file", Map.of("path", "b.txt")))));
        AgentResult result = AgentHarness.newLoop(workspace, fake, new AgentConfig(2), req -> false)
                .run("t");
        assertFalse(result.finished());
        // 部分成果保留：最后一次 LLM 的有效内容
        assertEquals("step 2 thinking", result.finalAnswer());
        assertEquals(2, result.toolCallCount(), "已完成的 Tool 调用计数不应丢失");
    }

    @Test
    void doesNotThrowWhenBudgetExhausted() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))));
        AgentResult result = AgentHarness.newLoop(workspace, fake, new AgentConfig(1), req -> false)
                .run("t");
        assertFalse(result.finished());
        assertTrue(result.error().contains("max iterations"));
    }

    @Test
    void toolCallsExecutedBeforeFailureAreKept() {
        // 第一轮工具成功执行，第二轮工具失败（越界），随后预算耗尽
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "write_file", Map.of("path", "ok.txt", "content", "ok")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "write_file", Map.of("path", "../evil.txt", "content", "x")))))
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c3", "read_file", Map.of("path", "a.txt")))));
        AgentResult result = AgentHarness.newLoop(workspace, fake, new AgentConfig(3), req -> true)
                .run("t");
        assertFalse(result.finished());
        assertEquals(3, result.toolCallCount(), "包括失败工具在内的全部执行计数应保留");
        // 越界写入未发生（安全），成功写入保留
        assertTrue(java.nio.file.Files.exists(workspace.resolve("ok.txt")));
        assertFalse(java.nio.file.Files.exists(workspace.resolve("../evil.txt")));
    }
}
