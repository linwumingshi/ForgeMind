package com.forgemind.llm.fake;

import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.llm.LlmStreamClient;
import com.forgemind.core.llm.LlmStreamListener;
import com.forgemind.core.llm.LlmStreamResult;
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
 * <p>M8 起实现 {@link LlmStreamClient}：{@link #stream} 把脚本响应的 content
 * 逐字符回调为 text delta（模拟真实流式），toolCalls 整块回调，随后 onComplete。
 * null 响应走 onComplete(null)（与 chat() 返回 null 一致，由 AgentLoop 计为可恢复
 * 畸形响应）；脚本抛出的 LlmException → onError（传输层故障）。</p>
 *
 * <p>API 保持最小：{@link #then} / {@link #thenThrow} / {@link #thenNull} /
 * {@link #calls} / {@link #provider}。</p>
 */
public final class FakeLlmClient implements LlmClient, LlmStreamClient {

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

    @Override
    public void stream(List<ChatMessage> messages, LlmStreamListener listener) {
        calls.add(List.copyOf(messages));
        Supplier<AgentResponse> next = script.poll();
        if (next == null) {
            throw new IllegalStateException("fake script exhausted");
        }
        AgentResponse response;
        try {
            response = next.get();
        } catch (LlmException e) {
            listener.onError(e);
            return;
        }
        if (response == null) {
            // 与 chat() 返回 null 语义一致：null 响应是可恢复的畸形响应，
            // 由 AgentLoop 计数并回灌提示，不是传输层故障（onError）。
            listener.onComplete(LlmStreamResult.of(null));
            return;
        }
        // 模拟流式：content 逐字符 text delta；toolCalls 整块 tool_call delta
        if (response.content() != null) {
            for (int i = 0; i < response.content().length(); i++) {
                listener.onTextDelta(String.valueOf(response.content().charAt(i)));
            }
        }
        if (response.toolCalls() != null) {
            for (com.forgemind.model.ToolCall call : response.toolCalls()) {
                listener.onToolCallDelta(call.id(), call.name(), null);
            }
        }
        listener.onComplete(LlmStreamResult.of(response));
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
