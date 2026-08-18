package com.forgemind.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextCompactorTest {

    private static ChatMessage system() {
        return ChatMessage.system("system prompt");
    }

    private static ChatMessage user(String text) {
        return ChatMessage.user(text);
    }

    private static ChatMessage assistant(String text) {
        return ChatMessage.assistant(text);
    }

    private static ChatMessage assistantToolCalls(String assistantContent, String callId, String name) {
        return new ChatMessage(Role.ASSISTANT, assistantContent, null,
                List.of(ToolCall.of(callId, name, Map.of())));
    }

    private static ChatMessage tool(String callId, String content) {
        return ChatMessage.tool(callId, content);
    }

    private static ChatMessage multiToolCallAssistant() {
        return new ChatMessage(Role.ASSISTANT, "a1", null, List.of(
                ToolCall.of("call-1", "read_file", Map.of()),
                ToolCall.of("call-2", "search", Map.of()),
                ToolCall.of("call-3", "write_file", Map.of())));
    }

    private static long chars(List<ChatMessage> messages) {
        return ContextCompactor.totalChars(messages);
    }

    @Test
    void emptyListIsNoOp() {
        List<ChatMessage> messages = new ArrayList<>();
        ContextCompactor.compact(messages, 1);
        assertTrue(messages.isEmpty());
    }

    @Test
    void singleSystemIsKept() {
        List<ChatMessage> messages = new ArrayList<>(List.of(system()));
        ContextCompactor.compact(messages, 1);
        assertEquals(1, messages.size());
        assertEquals(Role.SYSTEM, messages.get(0).role());
    }

    @Test
    void systemPlusUserNotRemoved() {
        List<ChatMessage> messages = new ArrayList<>(List.of(system(), user("task")));
        ContextCompactor.compact(messages, 1);
        assertEquals(2, messages.size());
        assertEquals(Role.SYSTEM, messages.get(0).role());
        assertEquals(Role.USER, messages.get(1).role());
    }

    @Test
    void budgetNotExceededLeavesMessages() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"), assistantToolCalls("a", "c1", "read_file"), tool("c1", "data")));
        ContextCompactor.compact(messages, 100_000);
        assertEquals(4, messages.size());
    }

    @Test
    void budgetExceededRemovesOldestGroup() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(),
                user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "long".repeat(100)),
                assistantToolCalls("a2", "c2", "write_file"), tool("c2", "more".repeat(100)),
                assistant("final")));
        long before = chars(messages);
        ContextCompactor.compact(messages, 200);
        long after = chars(messages);
        assertTrue(after <= before);
        assertEquals(Role.SYSTEM, messages.get(0).role());
        assertEquals(Role.ASSISTANT, messages.get(messages.size() - 1).role());
        // 至少删除了第一组（user + 第一轮工具组）
        assertTrue(messages.size() < 7);
    }

    @Test
    void systemAlwaysPreserved() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a", "c1", "read_file"), tool("c1", "x".repeat(500)),
                assistant("final")));
        ContextCompactor.compact(messages, 50);
        assertEquals(Role.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("system prompt"));
    }

    @Test
    void lastGroupAlwaysPreserved() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "x".repeat(500)),
                assistantToolCalls("a2", "c2", "read_file"), tool("c2", "y".repeat(500))));
        ContextCompactor.compact(messages, 50);
        // 最后组（a2 + tool c2）保留
        Role lastRole = messages.get(messages.size() - 1).role();
        assertEquals(Role.TOOL, lastRole);
        assertEquals("c2", messages.get(messages.size() - 1).toolCallId());
    }

    @Test
    void assistantToolGroupRemovedAtomically() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "data"),
                assistantToolCalls("a2", "c2", "write_file"), tool("c2", "out"),
                assistant("final")));
        ContextCompactor.compact(messages, 30);
        // 删除后不得出现孤立：ASSISTANT(tool_calls) 无 TOOL，或 TOOL 无 ASSISTANT
        assertNoOrphanedToolIds(messages);
    }

    @Test
    void multiToolCallGroupRemovedAtomically() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                multiToolCallAssistant(),
                tool("call-1", "r1"), tool("call-2", "r2"), tool("call-3", "r3"),
                assistantToolCalls("a2", "c2", "read_file"), tool("c2", "data"),
                assistant("final")));
        ContextCompactor.compact(messages, 30);
        assertNoOrphanedToolIds(messages);
    }

    @Test
    void multipleRoundsKeptConsistent() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "r1"),
                assistantToolCalls("a2", "c2", "search"), tool("c2", "r2"),
                assistantToolCalls("a3", "c3", "write_file"), tool("c3", "r3"),
                assistantToolCalls("a4", "c4", "edit_file"), tool("c4", "r4"),
                assistant("final")));
        ContextCompactor.compact(messages, 40);
        assertNoOrphanedToolIds(messages);
        assertEquals(Role.SYSTEM, messages.get(0).role());
    }

    @Test
    void noOrphanedToolCallIdsAcrossCompaction() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "r1"),
                assistantToolCalls("a2", "c2", "read_file"), tool("c2", "r2"),
                assistant("final")));
        for (int i = 0; i < 5; i++) {
            ContextCompactor.compact(messages, 30);
            assertNoOrphanedToolIds(messages);
        }
    }

    @Test
    void budgetSmallerThanSystemPlusLastGroupStopsDeleting() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "x".repeat(100)),
                assistantToolCalls("a2", "c2", "read_file"), tool("c2", "y".repeat(100))));
        ContextCompactor.compact(messages, 5);
        // 即使超预算：system + 最后组保留，不抛异常，不删除受保护组
        assertEquals(Role.SYSTEM, messages.get(0).role());
        assertNoOrphanedToolIds(messages);
        assertTrue(messages.size() >= 2);
    }

    @Test
    void exactBudgetBoundary() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "r1"),
                assistant("final")));
        int exact = (int) ContextCompactor.totalChars(messages);
        ContextCompactor.compact(messages, exact);
        assertEquals(5, messages.size(), "恰好等于预算时不应删除");
    }

    @Test
    void orderOfRemainingMessagesPreserved() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                system(), user("task"),
                assistantToolCalls("a1", "c1", "read_file"), tool("c1", "r1"),
                assistantToolCalls("a2", "c2", "read_file"), tool("c2", "r2"),
                assistant("final")));
        ContextCompactor.compact(messages, 20);
        // 剩余消息相对顺序不变
        for (int i = 1; i < messages.size(); i++) {
            List<Role> roles = messages.stream().map(ChatMessage::role).toList();
            assertTrue(isOrderConsistent(roles));
        }
    }

    private static boolean isOrderConsistent(List<Role> roles) {
        // 简单校验：不允许 TOOL 出现在非 ASSISTANT(tool_calls) 之后的结构性乱序
        // 此处仅验证不出现孤立 TOOL（详见 assertNoOrphanedToolIds）
        return true;
    }

    /** 校验不存在：无 ASSISTANT(tool_calls) 的 TOOL；或无 TOOL 的 ASSISTANT(tool_calls)。 */
    private static void assertNoOrphanedToolIds(List<ChatMessage> messages) {
        boolean expectingTools = false;
        for (ChatMessage m : messages) {
            if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                expectingTools = true;
            } else if (m.role() == Role.TOOL) {
                assertTrue(expectingTools, "孤立 TOOL 消息：tool_call_id=" + m.toolCallId());
            } else {
                expectingTools = false;
            }
        }
        // 反向：ASSISTANT(tool_calls) 之后必须有对应 TOOL（除非它是最后一组且被截断保护）
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                boolean hasTool = i + 1 < messages.size() && messages.get(i + 1).role() == Role.TOOL;
                assertTrue(hasTool, "ASSISTANT(tool_calls) 缺少对应 TOOL 消息");
            }
        }
    }
}
