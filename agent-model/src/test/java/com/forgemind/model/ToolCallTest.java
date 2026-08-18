package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallTest {

    @Test
    void keepsFields() {
        ToolCall call = ToolCall.of("call-1", "read_file", Map.of("path", "pom.xml"));
        assertEquals("call-1", call.id());
        assertEquals("read_file", call.name());
        assertEquals(Map.of("path", "pom.xml"), call.arguments());
    }

    @Test
    void nullArgumentsBecomeEmptyMap() {
        ToolCall call = new ToolCall("c1", "list_files", null);
        assertTrue(call.arguments().isEmpty());
    }

    @Test
    void argumentsAreDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("path", "a.txt");
        ToolCall call = ToolCall.of("c1", "read_file", mutable);
        mutable.put("path", "b.txt");
        assertEquals(Map.of("path", "a.txt"), call.arguments());
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("x", "y"));
    }

    @Test
    void nullIdOrNameRejected() {
        assertThrows(NullPointerException.class, () -> new ToolCall(null, "read_file", Map.of()));
        assertThrows(NullPointerException.class, () -> new ToolCall("c1", null, Map.of()));
    }
}
