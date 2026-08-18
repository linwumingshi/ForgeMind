package com.forgemind.llm.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmStreamListener;
import com.forgemind.core.llm.LlmStreamResult;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FakeLlmClientStreamTest {

    private static final class RecordingListener implements LlmStreamListener {
        final StringBuilder text = new StringBuilder();
        int toolCallDeltas;
        LlmStreamResult result;
        LlmException error;

        @Override
        public void onTextDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onToolCallDelta(String id, String name, String arguments) {
            toolCallDeltas++;
        }

        @Override
        public void onComplete(LlmStreamResult result) {
            this.result = result;
        }

        @Override
        public void onError(LlmException error) {
            this.error = error;
        }
    }

    @Test
    void fakeStreamsTextAsDeltas() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("你好世界"));
        RecordingListener l = new RecordingListener();
        fake.stream(List.of(ChatMessage.user("q")), l);
        // 逐字符 delta 累积后应还原完整文本
        assertEquals("你好世界", l.text.toString());
        assertNotNull(l.result);
        assertEquals("你好世界", l.result.response().content());
        assertNull(l.error);
        assertEquals(4, l.text.length());
    }

    @Test
    void fakeStreamsToolCall() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.withToolCalls(null,
                List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))));
        RecordingListener l = new RecordingListener();
        fake.stream(List.of(ChatMessage.user("q")), l);
        assertEquals(1, l.toolCallDeltas);
        assertNotNull(l.result);
        assertTrue(l.result.response().hasToolCalls());
        assertEquals("c1", l.result.response().toolCalls().get(0).id());
    }

    @Test
    void fakeStreamError() {
        FakeLlmClient fake = new FakeLlmClient().thenThrow(new LlmException("fake stream failure"));
        RecordingListener l = new RecordingListener();
        fake.stream(List.of(ChatMessage.user("q")), l);
        assertNotNull(l.error);
        assertTrue(l.error.getMessage().contains("fake stream failure"));
        assertNull(l.result);
    }

    @Test
    void fakeStreamNullResponseCompletesWithNull() {
        // 与 chat() 返回 null 语义一致：null 是可恢复畸形响应，走 onComplete(null)，
        // 由 AgentLoop 计数回灌；不得伪装成传输层故障（onError）。
        FakeLlmClient fake = new FakeLlmClient().thenNull();
        RecordingListener l = new RecordingListener();
        fake.stream(List.of(ChatMessage.user("q")), l);
        assertNull(l.error);
        assertNotNull(l.result);
        assertNull(l.result.response());
    }

    @Test
    void fakeStreamRecordsMessages() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        RecordingListener l = new RecordingListener();
        fake.stream(List.of(ChatMessage.user("task")), l);
        assertEquals(1, fake.calls().size());
        assertEquals("task", fake.calls().get(0).get(0).content());
    }
}
