package com.forgemind.core.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.exception.ConfigException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M9.1：SubAgentSpec 构造与校验。
 */
class SubAgentSpecTest {

    @Test
    void fullSpecIsValid() {
        SubAgentSpec spec = new SubAgentSpec("fix the bug", List.of("read_file", "edit_file"), 10);
        assertEquals("fix the bug", spec.task());
        assertEquals(List.of("read_file", "edit_file"), spec.tools());
        assertEquals(10, spec.maxIterations());
        assertFalse(spec.inheritsAllTools());
    }

    @Test
    void nullToolsMeansInheritAll() {
        SubAgentSpec spec = new SubAgentSpec("task", null, null);
        assertNull(spec.tools());
        assertNull(spec.maxIterations());
        assertTrue(spec.inheritsAllTools());
    }

    @Test
    void emptyToolsMeansInheritAll() {
        SubAgentSpec spec = new SubAgentSpec("task", List.of(), 5);
        assertTrue(spec.inheritsAllTools());
    }

    @Test
    void ofFactoryTaskOnly() {
        SubAgentSpec spec = SubAgentSpec.of("task");
        assertEquals("task", spec.task());
        assertTrue(spec.inheritsAllTools());
        assertNull(spec.maxIterations());
    }

    @Test
    void ofFactoryTaskAndTools() {
        SubAgentSpec spec = SubAgentSpec.of("task", List.of("shell"));
        assertEquals(List.of("shell"), spec.tools());
    }

    @Test
    void blankTaskRejected() {
        assertThrows(ConfigException.class, () -> new SubAgentSpec("", null, null));
        assertThrows(ConfigException.class, () -> new SubAgentSpec("   ", null, null));
        assertThrows(ConfigException.class, () -> new SubAgentSpec(null, null, null));
    }

    @Test
    void blankToolNameRejected() {
        assertThrows(ConfigException.class,
                () -> new SubAgentSpec("task", List.of("read_file", ""), null));
        assertThrows(ConfigException.class,
                () -> new SubAgentSpec("task", List.of("read_file", "  "), null));
    }

    @Test
    void nonPositiveMaxIterationsRejected() {
        assertThrows(ConfigException.class,
                () -> new SubAgentSpec("task", null, 0));
        assertThrows(ConfigException.class,
                () -> new SubAgentSpec("task", null, -1));
    }

    @Test
    void toolsAreDefensivelyCopied() {
        List<String> mutable = new java.util.ArrayList<>(List.of("read_file"));
        SubAgentSpec spec = new SubAgentSpec("task", mutable, null);
        mutable.add("shell");
        assertEquals(List.of("read_file"), spec.tools(), "构造后外部修改不得影响 spec");
    }

    @Test
    void valueSemantics() {
        SubAgentSpec a = new SubAgentSpec("t", List.of("x"), 3);
        SubAgentSpec b = new SubAgentSpec("t", List.of("x"), 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("t"));
    }
}
