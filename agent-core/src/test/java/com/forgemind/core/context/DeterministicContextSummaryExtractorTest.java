package com.forgemind.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.ContextSummary;
import com.forgemind.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicContextSummaryExtractorTest {

    @Test
    void extractsTaskFromFirstUserMessage() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("修改登录功能"),
                ChatMessage.assistant("ok"));
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(messages);
        assertEquals("修改登录功能", summary.task());
    }

    @Test
    void extractsModifiedFilesFromWriteAndEditCalls() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("task"),
                ChatMessage.assistantToolCalls(List.of(
                        ToolCall.of("c1", "write_file", Map.of("path", "src/A.java")),
                        ToolCall.of("c2", "edit_file", Map.of("path", "src/B.java")))));
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(messages);
        assertTrue(summary.modifiedFiles().contains("src/A.java"));
        assertTrue(summary.modifiedFiles().contains("src/B.java"));
    }

    @Test
    void extractsCommandsFromShellCalls() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("task"),
                ChatMessage.assistantToolCalls(List.of(
                        ToolCall.of("c1", "shell", Map.of("command", "mvn test")))));
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(messages);
        assertTrue(summary.commands().contains("mvn test"));
    }

    @Test
    void extractsTestResultsFromToolMessages() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("task"),
                ChatMessage.tool("c1", "[tool: shell]\n[success: true]\nTests run: 304, Failures: 0\nBUILD SUCCESS"));
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(messages);
        assertTrue(summary.testResults().stream().anyMatch(s -> s.contains("Tests run: 304")));
    }

    @Test
    void pendingWorkAndDecisionsStayEmpty() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("task"),
                ChatMessage.assistantToolCalls(List.of(ToolCall.of("c1", "read_file", Map.of("path", "a.txt")))));
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(messages);
        assertTrue(summary.pendingWork().isEmpty());
        assertTrue(summary.decisions().isEmpty());
    }

    @Test
    void emptyContextYieldsEmptySummary() {
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(List.of());
        assertTrue(summary.isEmpty());
    }

    @Test
    void noToolCallsMeansNoFilesOrCommands() {
        ContextSummary summary = DeterministicContextSummaryExtractor.extract(
                List.of(ChatMessage.user("task"), ChatMessage.assistant("thinking")));
        assertTrue(summary.modifiedFiles().isEmpty());
        assertTrue(summary.commands().isEmpty());
    }
}
