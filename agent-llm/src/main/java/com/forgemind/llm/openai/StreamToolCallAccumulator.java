package com.forgemind.llm.openai;

import com.forgemind.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * OpenAI-compatible 流式 tool_calls 增量聚合器（纯内存，确定性，无副作用）。
 *
 * <p>按 {@code index} 聚合多个 chunk 的 tool_call 分片：id / function.name /
 * function.arguments 可能跨 chunk 逐步出现，arguments 为 JSON <b>字符串分片</b>
 * （绝不逐片解析），完成时一次性解析。空 delta 忽略；缺失 index 按顺序分配。</p>
 */
final class StreamToolCallAccumulator {

    private final TreeMap<Integer, MutableToolCall> calls = new TreeMap<>();

    /**
     * 累积一个 tool_call delta 分片。
     *
     * @param index          OpenAI tool_calls 的 index
     * @param idDelta        id 分片（null/空表示本 chunk 无）
     * @param nameDelta      function.name 分片（null/空表示本 chunk 无）
     * @param argumentsDelta function.arguments 分片（null 表示无；空串拼入无害）
     */
    void onDelta(int index, String idDelta, String nameDelta, String argumentsDelta) {
        boolean empty = (idDelta == null || idDelta.isEmpty())
                && (nameDelta == null || nameDelta.isEmpty())
                && (argumentsDelta == null || argumentsDelta.isEmpty());
        if (empty) {
            return; // 空 delta 忽略
        }
        MutableToolCall call = calls.computeIfAbsent(index, k -> new MutableToolCall());
        if (idDelta != null && !idDelta.isEmpty()) {
            call.id.append(idDelta);
        }
        if (nameDelta != null && !nameDelta.isEmpty()) {
            call.name.append(nameDelta);
        }
        if (argumentsDelta != null) {
            call.arguments.append(argumentsDelta);
        }
    }

    /** 是否尚未收到任何有效分片。 */
    boolean isEmpty() {
        return calls.isEmpty();
    }

    /** 当前已出现的 index 数量（用于缺失 index 时按顺序分配）。 */
    int size() {
        return calls.size();
    }

    /**
     * 组装完整 ToolCall 列表（按 index 升序；id/name 缺失时为空串，交由
     * AgentLoop 畸形检测；arguments 一次性 JSON 解析，失败 → 空 Map）。
     */
    List<ToolCall> finish() {
        List<ToolCall> result = new ArrayList<>(calls.size());
        for (MutableToolCall call : calls.values()) {
            result.add(ToolCall.of(call.id.toString(), call.name.toString(),
                    OpenAiToolArguments.parse(call.arguments.toString())));
        }
        return result;
    }

    private static final class MutableToolCall {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
