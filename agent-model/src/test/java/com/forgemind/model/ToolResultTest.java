package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void successFactory() {
        ToolResult result = ToolResult.success("all good");
        assertTrue(result.success());
        assertEquals("all good", result.output());
        assertNull(result.error());
        assertNull(result.exitCode());
        assertFalse(result.truncated());
    }

    @Test
    void failureFactory() {
        ToolResult result = ToolResult.failure("boom");
        assertFalse(result.success());
        assertEquals("boom", result.error());
        assertNull(result.output());
    }

    @Test
    void withToolCallIdKeepsOtherFields() {
        ToolResult result = ToolResult.success("out").withToolCallId("call-9");
        assertEquals("call-9", result.toolCallId());
        assertTrue(result.success());
        assertEquals("out", result.output());
    }

    @Test
    void withExitCodeKeepsOtherFields() {
        ToolResult result = ToolResult.success("done").withExitCode(2);
        assertEquals(2, result.exitCode());
        assertTrue(result.success());
    }
}
