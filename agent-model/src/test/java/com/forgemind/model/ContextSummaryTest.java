package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextSummaryTest {

    @Test
    void emptySummary() {
        ContextSummary summary = ContextSummary.empty();
        assertTrue(summary.isEmpty());
        assertEquals("", summary.task());
    }

    @Test
    void nonEmptyIsDetected() {
        ContextSummary summary = new ContextSummary("fix bug", List.of(), List.of("src/Bug.java"),
                List.of(), List.of(), List.of(), List.of());
        assertFalse(summary.isEmpty());
    }

    @Test
    void nullListsBecomeEmpty() {
        ContextSummary summary = new ContextSummary("t", null, null, null, null, null, null);
        assertTrue(summary.facts().isEmpty());
        assertTrue(summary.modifiedFiles().isEmpty());
    }

    @Test
    void listsAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>();
        mutable.add("a.txt");
        ContextSummary summary = new ContextSummary("t", List.of(), mutable, List.of(), List.of(), List.of(), List.of());
        mutable.add("b.txt");
        assertEquals(1, summary.modifiedFiles().size());
        assertThrows(UnsupportedOperationException.class, () -> summary.modifiedFiles().add("x"));
    }

    @Test
    void renderContainsMarkersAndPopulatedFields() {
        ContextSummary summary = new ContextSummary(
                "修改登录功能",
                List.of(),
                List.of("src/UserController.java", "src/AuthService.java"),
                List.of("mvn test"),
                List.of("Tests run: 42, Failures: 0"),
                List.of(),
                List.of());
        String text = summary.render();
        assertTrue(text.startsWith("[CONTEXT SUMMARY]"));
        assertTrue(text.endsWith("[/CONTEXT SUMMARY]"));
        assertTrue(text.contains("task: 修改登录功能"));
        assertTrue(text.contains("modifiedFiles: [src/UserController.java, src/AuthService.java]"));
        assertTrue(text.contains("commands: [mvn test]"));
        assertTrue(text.contains("testResults: [Tests run: 42, Failures: 0]"));
        // 空字段不渲染
        assertFalse(text.contains("pendingWork"));
        assertFalse(text.contains("decisions"));
    }

    @Test
    void renderOfEmptyIsJustMarkers() {
        String text = ContextSummary.empty().render();
        assertEquals("[CONTEXT SUMMARY]\n[/CONTEXT SUMMARY]", text);
    }
}
