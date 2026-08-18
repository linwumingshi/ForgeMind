package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void factoryRoles() {
        assertEquals(Role.SYSTEM, ChatMessage.system("s").role());
        assertEquals(Role.USER, ChatMessage.user("u").role());
        assertEquals(Role.ASSISTANT, ChatMessage.assistant("a").role());
        assertEquals(Role.TOOL, ChatMessage.tool("call-1", "result").role());
    }

    @Test
    void toolMessageLinksToolCallId() {
        ChatMessage message = ChatMessage.tool("call-1", "file content");
        assertEquals("call-1", message.toolCallId());
        assertEquals("file content", message.content());
        assertNull(message.toolCalls());
    }

    @Test
    void assistantToolCallsMessage() {
        ChatMessage message = ChatMessage.assistantToolCalls(
                List.of(ToolCall.of("c1", "echo", java.util.Map.of())));
        assertEquals(Role.ASSISTANT, message.role());
        assertNull(message.content());
        assertEquals(1, message.toolCalls().size());
    }

    @Test
    void toolCallsAreDefensivelyCopied() {
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(ToolCall.of("c1", "echo", java.util.Map.of()));
        ChatMessage message = new ChatMessage(Role.ASSISTANT, null, null, mutable);
        mutable.clear();
        assertEquals(1, message.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> message.toolCalls().add(null));
    }

    @Test
    void nullRoleRejected() {
        assertThrows(NullPointerException.class, () -> new ChatMessage(null, "x", null, null));
    }
}
