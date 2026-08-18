package com.forgemind.core.llm;

import com.forgemind.model.AgentResponse;

/**
 * 一次流式 LLM 调用的最终结果：完整 {@link AgentResponse} + 可选真实 usage。
 *
 * <p>usage 仅用于统计/日志（请求前预算仍由 ApproximateTokenEstimator 负责），
 * 缺失时不伪造（{@code hasUsage=false}）。</p>
 */
public record LlmStreamResult(
        AgentResponse response,
        boolean hasUsage,
        long promptTokens,
        long completionTokens,
        long totalTokens) {

    public static LlmStreamResult of(AgentResponse response) {
        return new LlmStreamResult(response, false, 0, 0, 0);
    }

    public static LlmStreamResult of(AgentResponse response, long prompt, long completion, long total) {
        return new LlmStreamResult(response, true, prompt, completion, total);
    }
}
