package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentResponseTest {

    @Test
    void finalAnswerIsFinished() {
        AgentResponse response = AgentResponse.finalAnswer("done");
        assertTrue(response.isFinished());
        assertFalse(response.hasToolCalls());
        assertEquals("done", response.content());
        assertNull(response.toolCalls());
    }

    @Test
    void withToolCallsIsNotFinished() {
        AgentResponse response = AgentResponse.withToolCalls(
                null, List.of(ToolCall.of("c1", "echo", java.util.Map.of())));
        assertTrue(response.hasToolCalls());
        assertFalse(response.isFinished());
    }

    @Test
    void toolCallsAreDefensivelyCopied() {
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(ToolCall.of("c1", "echo", java.util.Map.of()));
        AgentResponse response = new AgentResponse(null, mutable);
        mutable.clear();
        assertEquals(1, response.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> response.toolCalls().add(null));
    }
}
