package com.forgemind.llm.fake;

import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * 测试用 Fake LLM：按脚本顺序返回预设响应，并记录每轮收到的完整消息序列。
 *
 * <p>不依赖任何真实网络 / 模型 / 配置。脚本耗尽后 {@link #chat} 抛
 * {@link IllegalStateException}（提示测试脚本编写错误）。</p>
 *
 * <p>API 保持最小：{@link #then} / {@link #thenThrow} / {@link #thenNull} /
 * {@link #calls} / {@link #provider}。</p>
 */
public final class FakeLlmClient implements LlmClient {

    private final Queue<Supplier<AgentResponse>> script = new ArrayDeque<>();
    private final List<List<ChatMessage>> calls = new ArrayList<>();

    @Override
    public String provider() {
        return "fake";
    }

    /** 追加一个正常响应。 */
    public FakeLlmClient then(AgentResponse response) {
        Objects.requireNonNull(response, "response");
        script.add(() -> response);
        return this;
    }

    /** 追加一次调用即抛出的 LLM 异常（模拟 LLM 故障）。 */
    public FakeLlmClient thenThrow(LlmException error) {
        Objects.requireNonNull(error, "error");
        script.add(() -> {
            throw error;
        });
        return this;
    }

    /** 追加一次返回 null 的响应（模拟非法输出）。 */
    public FakeLlmClient thenNull() {
        script.add(() -> null);
        return this;
    }

    @Override
    public AgentResponse chat(List<ChatMessage> messages) {
        calls.add(List.copyOf(messages));
        Supplier<AgentResponse> next = script.poll();
        if (next == null) {
            throw new IllegalStateException("fake script exhausted");
        }
        return next.get();
    }

    /** 每轮 chat 收到的消息快照（不可变，按调用顺序）。 */
    public List<List<ChatMessage>> calls() {
        return calls;
    }

    /** 已被调用的轮数。 */
    public int callCount() {
        return calls.size();
    }
}
