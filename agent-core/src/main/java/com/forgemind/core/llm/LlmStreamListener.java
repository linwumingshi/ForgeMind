package com.forgemind.core.llm;

import com.forgemind.core.exception.LlmException;

/**
 * 流式 LLM 回调（同步阻塞语义：所有回调在调用线程内完成）。
 *
 * <p>事件顺序：{@code onTextDelta} / {@code onToolCallDelta} 若干次 →
 * 最后恰好一次 {@code onComplete}（携带完整结果）或 {@code onError}。
 * 不暴露任何 SSE / provider 专有概念。</p>
 */
public interface LlmStreamListener {

    /** 文本增量（累积后为最终 content；可为空串）。 */
    void onTextDelta(String textDelta);

    /**
     * Tool Call 增量（id / function.name / function.arguments 分片，
     * 可能跨多次回调逐步出现）。
     */
    void onToolCallDelta(String idDelta, String nameDelta, String argumentsDelta);

    /** 流正常结束，携带完整结果（含最终 AgentResponse 与可选 usage）。 */
    void onComplete(LlmStreamResult result);

    /** 流失败（HTTP 错误、body 中断、超时等）；不会再有其他回调。 */
    void onError(LlmException error);
}
