package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.exception.ToolTimeoutException;
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
 * Agent 闭环错误处理测试：Tool 错误（自纠）、权限、路径越界、Tool 异常、
 * LLM 故障、畸形响应（含混合非法 Tool Call）。
 */
class AgentLoopErrorHandlingTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "hello a");
    }

    // ---------- Tool 错误：可恢复，回灌后 LLM 自纠 ----------

    @Test
    void unknownToolFeedsBackAndRecovers() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "no_such_tool", Map.of()))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished());
        assertEquals("recovered", result.finalAnswer());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("unknown tool"));
    }

    @Test
    void missingArgumentFeedsBackAndRecovers() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of()))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("invalid arguments"));
        assertTrue(toolMsg.content().contains("missing required argument 'path'"));
    }

    @Test
    void wrongArgumentTypeFeedsBack() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", 123)))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false).run("t");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("invalid arguments"));
        assertTrue(toolMsg.content().contains("must be of type string"));
    }

    // ---------- 权限 ----------

    @Test
    void permissionDenyFeedsBackAndDoesNotExecute() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "write_file", Map.of("path", "x.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("permission rejected"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished());
        assertFalse(Files.exists(workspace.resolve("x.txt")), "权限拒绝时不应写入");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"));
    }

    @Test
    void permissionAskAllowedExecutes() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "write_file", Map.of("path", "y.txt", "content", "ok")))))
                .then(AgentResponse.finalAnswer("written"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> true)
                .run("t");
        assertTrue(result.finished());
        assertTrue(Files.exists(workspace.resolve("y.txt")));
    }

    // ---------- 路径越界 ----------

    @Test
    void pathEscapeFeedsBack() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "write_file", Map.of("path", "../evil.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> true).run("t");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("path rejected"));
        assertFalse(Files.exists(workspace.resolve("../evil.txt")));
    }

    // ---------- Tool 异常：转为 failure 回灌，不崩溃 ----------

    @Test
    void toolTimeoutBecomesFailureResultNotCrash() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "broken", Map.of()))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(),
                req -> false, new BrokenTool(new ToolTimeoutException("slow"))).run("t");
        assertTrue(result.finished(), "Tool 抛异常不应导致 Agent 崩溃");
        assertEquals("recovered", result.finalAnswer());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertFalse(toolMsg.content().contains("[success: true]"));
        assertTrue(toolMsg.content().contains("ERROR:"));
    }

    @Test
    void toolRuntimeExceptionBecomesFailureResult() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "broken", Map.of()))))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(),
                req -> false, new BrokenTool(new IllegalStateException("boom"))).run("t");
        assertTrue(result.finished());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("ERROR:"));
        assertTrue(toolMsg.content().contains("boom"));
    }

    // ---------- LLM 故障 ----------

    @Test
    void llmExceptionTerminatesWithFailedResult() {
        FakeLlmClient fake = new FakeLlmClient().thenThrow(new LlmException("api down"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertFalse(result.finished());
        assertTrue(result.error().contains("api down"));
    }

    // ---------- 畸形响应 ----------

    @Test
    void nullResponseOnceThenRecovers() {
        FakeLlmClient fake = new FakeLlmClient()
                .thenNull()
                .then(AgentResponse.finalAnswer("ok"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished(), "一次畸形响应应允许 LLM 自纠");
        assertEquals("ok", result.finalAnswer());
        // 畸形后应回灌一条反馈消息
        ChatMessage last = fake.calls().get(1).get(fake.calls().get(1).size() - 1);
        assertEquals(Role.USER, last.role());
        assertTrue(last.content().contains("invalid response"));
    }

    @Test
    void consecutiveNullResponsesTerminateWithFailedResult() {
        FakeLlmClient fake = new FakeLlmClient()
                .thenNull().thenNull().thenNull();
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertFalse(result.finished());
        assertTrue(result.error().contains("invalid responses"));
        assertTrue(result.error().contains("3"));
    }

    @Test
    void mixedInvalidToolCallMarksWholeResponseInvalid() {
        // 关键场景：一次响应含 1 个合法 + 1 个非法（name 为空）Tool Call
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        ToolCall.of("ok", "read_file", Map.of("path", "a.txt")),
                        ToolCall.of("bad", "", Map.of()))))
                .then(AgentResponse.finalAnswer("retry-ok"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished());
        // 整轮 invalid：任何工具都不得执行（第二轮无 TOOL 消息），且回灌反馈
        List<ChatMessage> second = fake.calls().get(1);
        assertTrue(second.stream().noneMatch(m -> m.role() == Role.TOOL),
                "只要有一个 Tool Call 非法，整个响应都不能执行任何工具");
        ChatMessage last = second.get(second.size() - 1);
        assertEquals(Role.USER, last.role());
        assertTrue(last.content().contains("invalid response"));
    }

    @Test
    void emptyToolCallIdMarksWholeResponseInvalid() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        ToolCall.of("", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("retry-ok"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false).run("t");
        List<ChatMessage> second = fake.calls().get(1);
        assertTrue(second.stream().noneMatch(m -> m.role() == Role.TOOL));
    }

    @Test
    void emptyResponseIsInvalid() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(new AgentResponse("", null))
                .then(AgentResponse.finalAnswer("ok"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished(), "空响应一次应允许自纠");
    }

    @Test
    void validResponseResetsInvalidCounter() {
        // null(1) → 合法 tool call(归零) → null(1) → null(2) → final(完成)
        FakeLlmClient fake = new FakeLlmClient()
                .thenNull()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .thenNull()
                .thenNull()
                .then(AgentResponse.finalAnswer("done"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("t");
        assertTrue(result.finished(), "合法响应后畸形计数必须归零");
        assertEquals("done", result.finalAnswer());
    }
}
