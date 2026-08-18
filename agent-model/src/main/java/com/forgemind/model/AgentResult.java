package com.forgemind.model;

/**
 * 一次 Agent 运行的结果。
 *
 * <p>{@code finished=true} 表示正常产出最终答案；否则携带 {@code error} 说明
 * 终止原因（迭代预算耗尽 / LLM 故障等），此时 {@code finalAnswer} 可能是
 * 运行过程中产生的部分成果。</p>
 */
public record AgentResult(
        String finalAnswer,
        int iterations,
        int toolCallCount,
        boolean finished,
        String error) {

    public static AgentResult completed(String finalAnswer, int iterations, int toolCallCount) {
        return new AgentResult(finalAnswer, iterations, toolCallCount, true, null);
    }

    public static AgentResult failed(String partialAnswer, int iterations, int toolCallCount, String error) {
        return new AgentResult(partialAnswer, iterations, toolCallCount, false, error);
    }
}
