package com.forgemind.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * OpenAI-compatible tool arguments JSON 解析（与 OpenAiCompatibleLlmClient 既有语义一致）：
 * 空/非法 JSON/非 object → 空 Map（由 AgentLoop/ToolExecutor 参数校验回灌自纠）。
 */
final class OpenAiToolArguments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiToolArguments() {
    }

    static Map<String, Object> parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
