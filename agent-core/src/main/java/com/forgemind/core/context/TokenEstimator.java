package com.forgemind.core.context;

import com.forgemind.model.ChatMessage;
import java.util.List;

/**
 * Token 估算抽象（用于 Context Budget 控制，不是模型真实 tokenizer）。
 *
 * <p>实现必须是确定性的近似算法：单调、稳定、可测试，不依赖网络与第三方
 * tokenizer。默认实现 {@link #DEFAULT} = {@link ApproximateTokenEstimator}。</p>
 */
public interface TokenEstimator {

    /** 默认近似估算器（ASCII≈4 chars/token，CJK≈1.5 chars/token，含消息结构开销）。 */
    TokenEstimator DEFAULT = new ApproximateTokenEstimator();

    /** 估算一段文本的近似 token 数。 */
    long estimate(String text);

    /** 估算单条消息的近似 token 数（含 role / tool_calls / tool_call_id 开销）。 */
    long estimate(ChatMessage message);

    /** 估算整个消息列表的近似 token 数。 */
    long estimate(List<ChatMessage> messages);
}
