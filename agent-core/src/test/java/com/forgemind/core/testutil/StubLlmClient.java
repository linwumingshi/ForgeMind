package com.forgemind.core.testutil;

import com.forgemind.core.llm.LlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试专用 LlmClient：按脚本顺序依次返回预设响应，并记录每次收到的消息。
 * 脚本耗尽后抛 IllegalStateException（测试编写错误提示）。
 */
public final class StubLlmClient implements LlmClient {

    private final ArrayDeque<AgentResponse> script = new ArrayDeque<>();
    private final List<List<ChatMessage>> calls = new ArrayList<>();

    public StubLlmClient(AgentResponse... responses) {
        script.addAll(Arrays.asList(responses));
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public AgentResponse chat(List<ChatMessage> messages) {
        calls.add(List.copyOf(messages));
        AgentResponse next = script.poll();
        if (next == null) {
            throw new IllegalStateException("stub script exhausted");
        }
        return next;
    }

    /** 每次 chat 调用收到的消息快照（按调用顺序）。 */
    public List<List<ChatMessage>> calls() {
        return calls;
    }
}
