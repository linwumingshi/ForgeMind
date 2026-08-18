package com.forgemind.core.llm;

import com.forgemind.model.ChatMessage;
import java.util.List;

/**
 * 流式 LLM 能力扩展（可选能力）。
 *
 * <p>实现该接口的 LlmClient 额外支持 SSE 流式输出；不实现则回退
 * {@link #chat}。本接口只负责 LLM transport + delta 聚合，不感知
 * Permission / ToolExecutor / WorkspaceAccess / Context / AgentLoop。</p>
 */
public interface LlmStreamClient extends LlmClient {

    /**
     * 阻塞式流式调用：同步读取响应流，逐 delta 回调 {@code listener}，
     * 结束时 {@link LlmStreamListener#onComplete} 或 {@link LlmStreamListener#onError}。
     *
     * @param messages 完整对话历史
     * @param listener 回调监听器（非 null）
     */
    void stream(List<ChatMessage> messages, LlmStreamListener listener);
}
