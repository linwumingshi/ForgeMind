package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证模型与 JSON 的相互转换：序列化结构清晰、null 字段省略、
 * Tool Call 与 Tool Result 能正确关联。
 */
class JsonSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolCallRoundTrip() throws Exception {
        ToolCall call = ToolCall.of("call-1", "read_file", Map.of("path", "pom.xml"));
        String json = mapper.writeValueAsString(call);
        assertTrue(json.contains("\"id\":\"call-1\""));
        assertTrue(json.contains("\"name\":\"read_file\""));
        assertTrue(json.contains("\"path\":\"pom.xml\""));

        ToolCall parsed = mapper.readValue(json, ToolCall.class);
        assertEquals(call, parsed);
    }

    @Test
    void chatMessageWithToolCallsOmitsNullContent() throws Exception {
        ChatMessage message = ChatMessage.assistantToolCalls(
                List.of(ToolCall.of("c1", "echo", Map.of("text", "hi"))));
        String json = mapper.writeValueAsString(message);
        assertTrue(json.contains("\"role\":\"ASSISTANT\""));
        assertTrue(json.contains("\"toolCalls\""));
        assertFalse(json.contains("\"content\""), "null content 应被 NON_NULL 省略");

        ChatMessage parsed = mapper.readValue(json, ChatMessage.class);
        assertEquals(Role.ASSISTANT, parsed.role());
        assertEquals(1, parsed.toolCalls().size());
        assertEquals("c1", parsed.toolCalls().get(0).id());
    }

    @Test
    void toolMessageRoundTripKeepsCallId() throws Exception {
        ChatMessage message = ChatMessage.tool("call-42", "file content");
        String json = mapper.writeValueAsString(message);
        assertTrue(json.contains("\"toolCallId\":\"call-42\""));

        ChatMessage parsed = mapper.readValue(json, ChatMessage.class);
        assertEquals(Role.TOOL, parsed.role());
        assertEquals("call-42", parsed.toolCallId());
        assertEquals("file content", parsed.content());
    }

    @Test
    void agentResponseRoundTrip() throws Exception {
        AgentResponse response = AgentResponse.withToolCalls(
                "thinking", List.of(ToolCall.of("c1", "search", Map.of("query", "TODO"))));
        String json = mapper.writeValueAsString(response);
        AgentResponse parsed = mapper.readValue(json, AgentResponse.class);
        assertEquals("thinking", parsed.content());
        assertTrue(parsed.hasToolCalls());
        assertEquals("search", parsed.toolCalls().get(0).name());
    }

    @Test
    void toolSchemaSerializesPropertiesAndRequired() throws Exception {
        ToolSchema schema = ToolSchema.of(
                Map.of("path", new ToolParameter("string", "file path")),
                List.of("path"));
        String json = mapper.writeValueAsString(schema);
        assertTrue(json.contains("\"properties\""));
        assertTrue(json.contains("\"required\":[\"path\"]"));

        ToolSchema parsed = mapper.readValue(json, ToolSchema.class);
        assertEquals("string", parsed.properties().get("path").type());
        assertEquals(List.of("path"), parsed.required());
    }
}
