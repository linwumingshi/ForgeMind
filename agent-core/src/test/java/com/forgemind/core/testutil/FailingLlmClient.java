package com.forgemind.core.testutil;

import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.util.List;

/** 测试专用 LlmClient：每次调用必然抛出 LlmException。 */
public final class FailingLlmClient implements LlmClient {

    @Override
    public String provider() {
        return "failing";
    }

    @Override
    public AgentResponse chat(List<ChatMessage> messages) {
        throw new LlmException("api down");
    }
}
