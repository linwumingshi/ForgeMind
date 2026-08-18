package com.forgemind.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ToolCall;
import java.util.List;

/**
 * OpenAI-compatible 流式响应聚合器（纯内存，确定性）。
 *
 * <p>输入：SSE {@code data} 载荷（JSON chunk 文本）；输出：完整 {@link AgentResponse}
 * （text + tool_calls + finishReason），并可选记录 usage（最后 chunk）。
 * 本类不执行 Tool、不触碰 Context/AgentLoop/Retry。</p>
 *
 * <p>容错：malformed JSON chunk 忽略；无 choices / 空 choices 的 chunk 仅用于
 * usage；delta.content=null、tool_calls=null、finish_reason=null 均安全处理；
 * usage 缺失不伪造。</p>
 */
public final class OpenAiStreamAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder text = new StringBuilder();
    private final StreamToolCallAccumulator toolCalls = new StreamToolCallAccumulator();
    private String finishReason;
    private boolean hasUsage;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;

    /** 累积一个 chunk 的 data 载荷（OpenAI-compatible JSON）。 */
    public void accept(String dataPayload) {
        accept(dataPayload, null);
    }

    /**
     * 累积一个 chunk 并回调增量观察者（用于流式 UI；{@code observer} 可为 null）。
     */
    public void accept(String dataPayload, DeltaObserver observer) {
        if (dataPayload == null || dataPayload.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(dataPayload);
        } catch (Exception e) {
            return; // malformed chunk：忽略，不崩溃
        }
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            // usage 可能出现在 choices 为空的最后 chunk
            JsonNode usageNode = root.get("usage");
            if (usageNode != null && usageNode.isObject()) {
                recordUsage(usageNode);
            }
            return;
        }
        JsonNode choice = choices.get(0);
        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull()) {
            finishReason = finish.asText();
        }
        JsonNode delta = choice.get("delta");
        if (delta == null || delta.isNull() || !delta.isObject()) {
            return;
        }
        JsonNode content = delta.get("content");
        if (content != null && !content.isNull()) {
            String textDelta = content.asText();
            text.append(textDelta);
            if (observer != null) {
                observer.onTextDelta(textDelta);
            }
        }
        JsonNode toolCallsNode = delta.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray()) {
            for (JsonNode callNode : toolCallsNode) {
                acceptToolCallDelta(callNode, observer);
            }
        }
    }

    private void acceptToolCallDelta(JsonNode callNode, DeltaObserver observer) {
        if (callNode == null || !callNode.isObject()) {
            return;
        }
        int index = callNode.hasNonNull("index")
                ? callNode.get("index").asInt() : toolCalls.size();
        String id = callNode.hasNonNull("id") ? callNode.get("id").asText() : null;
        JsonNode function = callNode.get("function");
        String name = (function != null && function.hasNonNull("name"))
                ? function.get("name").asText() : null;
        String argumentsDelta = (function != null && function.hasNonNull("arguments"))
                ? function.get("arguments").asText() : null;
        boolean empty = (id == null || id.isEmpty())
                && (name == null || name.isEmpty())
                && (argumentsDelta == null || argumentsDelta.isEmpty());
        if (empty) {
            return;
        }
        toolCalls.onDelta(index, id, name, argumentsDelta);
        if (observer != null) {
            observer.onToolCallDelta(index, id, name, argumentsDelta);
        }
    }

    /** 流式增量观察者（agent-llm 内部，不暴露 SSE 概念到 core）。 */
    public interface DeltaObserver {
        void onTextDelta(String text);

        void onToolCallDelta(int index, String id, String name, String arguments);
    }

    private void recordUsage(JsonNode usageNode) {
        promptTokens = usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : 0;
        completionTokens = usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asLong() : 0;
        totalTokens = usageNode.hasNonNull("total_tokens") ? usageNode.get("total_tokens").asLong() : 0;
        hasUsage = true;
    }

    /**
     * 组装完整响应（在流结束后调用）。
     * content 为空串且无 tool_calls 时，AgentResponse("", null, reason)
     * 交由 AgentLoop 的既有空响应判定。
     */
    public AgentResponse finish() {
        List<ToolCall> calls = toolCalls.finish();
        if (calls.isEmpty()) {
            return AgentResponse.withFinishReason(text.toString(), null, finishReason);
        }
        return AgentResponse.withFinishReason(text.toString(), calls, finishReason);
    }

    public boolean hasUsage() {
        return hasUsage;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    /** 是否尚未收到任何有效内容（无文本、无 tool_call、无 reason、无 usage）。 */
    public boolean isEmpty() {
        return text.length() == 0 && toolCalls.isEmpty()
                && finishReason == null && !hasUsage;
    }
}
