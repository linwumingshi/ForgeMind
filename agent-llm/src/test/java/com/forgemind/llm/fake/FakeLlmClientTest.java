package com.forgemind.llm.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.exception.LlmException;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

    @Test
    void returnsScriptedResponsesInOrder() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("first"))
                .then(AgentResponse.finalAnswer("second"));
        assertEquals("first", fake.chat(List.of()).content());
        assertEquals("second", fake.chat(List.of()).content());
        assertEquals(2, fake.callCount());
    }

    @Test
    void providerIsFake() {
        assertEquals("fake", new FakeLlmClient().provider());
    }

    @Test
    void thenThrowPropagatesLlmException() {
        FakeLlmClient fake = new FakeLlmClient().thenThrow(new LlmException("api down"));
        LlmException e = assertThrows(LlmException.class, () -> fake.chat(List.of()));
        assertTrue(e.getMessage().contains("api down"));
    }

    @Test
    void thenNullReturnsNull() {
        FakeLlmClient fake = new FakeLlmClient().thenNull();
        assertNull(fake.chat(List.of()));
    }

    @Test
    void scriptExhaustionIsDetected() {
        FakeLlmClient fake = new FakeLlmClient();
        assertThrows(IllegalStateException.class, () -> fake.chat(List.of()));
    }

    @Test
    void recordsMessageSnapshotsPerCall() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("hello"));
        fake.chat(messages);

        // 调用方后续修改原列表不影响已记录的快照
        messages.clear();
        List<ChatMessage> recorded = fake.calls().get(0);
        assertEquals(1, recorded.size());
        assertEquals(Role.USER, recorded.get(0).role());
        assertEquals("hello", recorded.get(0).content());
        assertThrows(UnsupportedOperationException.class, () -> recorded.add(null));
    }

    @Test
    void recordsToolCallResponses() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("done"));
        fake.chat(List.of(ChatMessage.assistantToolCalls(
                List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt"))))));
        ChatMessage assistant = fake.calls().get(0).get(0);
        assertEquals(Role.ASSISTANT, assistant.role());
        assertEquals("c1", assistant.toolCalls().get(0).id());
        assertEquals("read_file", assistant.toolCalls().get(0).name());
    }
}
