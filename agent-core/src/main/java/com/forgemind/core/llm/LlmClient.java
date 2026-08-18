package com.forgemind.core.llm;

import com.forgemind.core.exception.LlmException;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.util.List;

/**
 * LLM 抽象：所有模型接入（OpenAI / Anthropic / DeepSeek / OpenAI-Compatible）
 * 都实现本接口，Agent 只依赖本接口，不感知任何厂商实现。
 */
public interface LlmClient {

    /** 实现标识，如 "openai-compatible"、"fake"。 */
    String provider();

    /**
     * 发送完整消息历史，返回结构化响应（最终文本或 Tool Call 列表）。
     *
     * @param messages 完整对话历史（由调用方维护）
     * @return 结构化响应
     * @throws LlmException 不可恢复的调用失败
     */
    AgentResponse chat(List<ChatMessage> messages);
}
