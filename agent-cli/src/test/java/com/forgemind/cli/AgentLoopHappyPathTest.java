package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Agent 闭环正常流程测试：直接答案 / 一问一工具 / 单轮多 Tool 并行 / tool_call_id 关联。
 */
class AgentLoopHappyPathTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "content A");
        Files.writeString(workspace.resolve("b.txt"), "content B");
    }

    @Test
    void userQuestionReturnsFinalAnswerDirectly() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("42"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("what is 6*7");
        assertTrue(result.finished());
        assertEquals("42", result.finalAnswer());
        assertEquals(1, result.iterations());
        assertEquals(0, result.toolCallCount());
    }

    @Test
    void singleToolCallThenFinalAnswer() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("call-1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("done"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("read a.txt");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolCallCount());

        // 第二轮消息：TOOL 消息必须携带正确 tool_call_id 与完整元数据 + 文件内容
        List<ChatMessage> second = fake.calls().get(1);
        ChatMessage toolMsg = second.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("call-1", toolMsg.toolCallId());
        assertTrue(toolMsg.content().contains("[tool: read_file]"));
        assertTrue(toolMsg.content().contains("[success: true]"));
        assertTrue(toolMsg.content().contains("content A"));
        assertFalse(toolMsg.content().contains("[stderr]"), "普通文件读取不应出现 stderr 分节");
    }

    @Test
    void multipleToolCallsInOneResponseAllExecuted() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, List.of(
                        ToolCall.of("c1", "read_file", Map.of("path", "a.txt")),
                        ToolCall.of("c2", "read_file", Map.of("path", "b.txt")))))
                .then(AgentResponse.finalAnswer("both read"));
        AgentResult result = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false)
                .run("read both");
        assertTrue(result.finished());
        assertEquals(2, result.toolCallCount());

        List<ChatMessage> second = fake.calls().get(1);
        List<ChatMessage> toolMsgs = second.stream().filter(m -> m.role() == Role.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        // 两个 Tool Call 的结果分别关联各自 id，内容互不混淆
        ChatMessage first = toolMsgs.get(0);
        ChatMessage secondMsg = toolMsgs.get(1);
        assertEquals("c1", first.toolCallId());
        assertEquals("c2", secondMsg.toolCallId());
        assertTrue(first.content().contains("content A"));
        assertTrue(secondMsg.content().contains("content B"));
        assertFalse(first.content().contains("content B"));
    }

    @Test
    void toolCallIdIsCarriedInToolMessageNotMergedIntoText() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("xyz-9", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("ok"));
        AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> false).run("t");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("xyz-9", toolMsg.toolCallId());
        // tool_call_id 存在于消息字段而非正文中
        assertFalse(toolMsg.content().contains("xyz-9"));
    }
}
