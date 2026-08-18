package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8.4：Streaming 增量不得写入 AgentContext —— Context 只保存完整
 * AssistantMessage（完整 content + 完整 tool_calls）与 ToolResult，
 * 并且 tool_call_id 与 TOOL 消息严格配对。增量仅经 ProgressListener 展示。
 */
class AgentLoopStreamContextTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
    }

    private AgentLoop buildAgent(FakeLlmClient fake, ProgressListener progress) {
        return com.forgemind.cli.AgentHarness.newLoop(workspace, fake,
                AgentConfig.defaults(), req -> true, progress);
    }

    @Test
    void deltasNeverEnterContextOnlyCompleteMessagesDo() {
        List<String> deltas = new ArrayList<>();
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("scanning the file",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("all done"));
        AgentResult result = buildAgent(fake, new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
                deltas.add(delta);
            }
        }).run("read a.txt");

        assertTrue(result.finished());
        assertEquals("all done", result.finalAnswer());
        // streaming 通道确实被使用（Fake 逐字符产生 delta）
        assertEquals("scanning the fileall done", String.join("", deltas));

        // 第二轮 LLM 收到的 Context：完整 Assistant（content + toolCalls）后跟 TOOL 结果
        List<ChatMessage> round2 = fake.calls().get(1);
        ChatMessage assistant = round2.stream()
                .filter(m -> m.role() == Role.ASSISTANT).findFirst().orElseThrow();
        // 完整 content，而非增量分片
        assertEquals("scanning the file", assistant.content());
        assertNotNull(assistant.toolCalls());
        assertEquals(1, assistant.toolCalls().size());
        assertEquals("c1", assistant.toolCalls().get(0).id());

        // tool_call_id 严格配对：TOOL 消息携带 c1
        ChatMessage toolMsg = round2.stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("c1", toolMsg.toolCallId());
        assertTrue(toolMsg.content().contains("[tool: read_file]"));

        // Context 中不存在任何单字符增量分片（delta 从未落盘 Context）
        for (List<ChatMessage> round : fake.calls()) {
            for (ChatMessage m : round) {
                assertTrue(m.content() == null || m.content().length() != 1
                                || !deltas.contains(m.content()),
                        "delta 不应作为独立消息进入 Context: " + m.content());
            }
        }
    }

    @Test
    void assistantContentWithToolCallsKeptWholeInContext() {
        // 多字符 content + 多 tool_call：Context 必须整条保存，不得拆分
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls("step one and step two",
                        List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")),
                                ToolCall.of("c2", "list_files", Map.of()))))
                .then(AgentResponse.finalAnswer("done both"));
        AgentResult result = buildAgent(fake, ProgressListener.NOOP).run("task");

        assertTrue(result.finished());
        List<ChatMessage> round2 = fake.calls().get(1);
        ChatMessage assistant = round2.stream()
                .filter(m -> m.role() == Role.ASSISTANT).findFirst().orElseThrow();
        assertEquals("step one and step two", assistant.content());
        assertEquals(List.of("c1", "c2"),
                assistant.toolCalls().stream().map(ToolCall::id).toList());

        // 两条 TOOL 结果按顺序配对
        List<ChatMessage> toolMsgs = round2.stream()
                .filter(m -> m.role() == Role.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        assertEquals("c1", toolMsgs.get(0).toolCallId());
        assertEquals("c2", toolMsgs.get(1).toolCallId());
    }
}
