package com.forgemind.core.context;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.ToolCall;
import java.util.List;

/**
 * 确定性近似 Token 估算器（用于 Context Budget，非精确计费）。
 *
 * <p>近似规则：</p>
 * <ul>
 *   <li>ASCII（{@code < 0x80}）：约 4 字符 / token（向上取整）；</li>
 *   <li>CJK 统一表意文字（U+4E00–U+9FFF）：约 1.5 字符 / token（即每 3 字符 2 token，向上取整）；</li>
 *   <li>其他 Unicode：约 2 字符 / token；</li>
 *   <li>每条消息固定结构开销 + role；每条 tool_call 追加 id/name/arguments 开销与
 *       tool_call_id 开销。</li>
 * </ul>
 *
 * <p>保证：文本估算永不为 0（空串返回 0 但整条消息估算 ≥ 结构开销）；单调递增。</p>
 */
public final class ApproximateTokenEstimator implements TokenEstimator {

    private static final long MESSAGE_OVERHEAD = 8;
    private static final long TOOL_CALL_OVERHEAD = 5;
    private static final long TOOL_CALL_ID_OVERHEAD = 3;

    private static final double ASCII_CHARS_PER_TOKEN = 4.0;
    private static final double CJK_CHARS_PER_TOKEN = 1.5;

    @Override
    public long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                ascii++;
            } else if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        long tokens = ceilDiv(ascii, (long) ASCII_CHARS_PER_TOKEN)
                + ceilDiv(cjk * 2, 3) // 1.5 chars/token → 2 token / 3 chars
                + ceilDiv(other, 2);
        return Math.max(1, tokens);
    }

    @Override
    public long estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        long tokens = MESSAGE_OVERHEAD + estimate(message.content());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                tokens += TOOL_CALL_OVERHEAD;
                tokens += estimate(call.id());
                tokens += estimate(call.name());
                tokens += estimate(String.valueOf(call.arguments()));
            }
        }
        if (message.toolCallId() != null) {
            tokens += TOOL_CALL_ID_OVERHEAD + estimate(message.toolCallId());
        }
        return Math.max(1, tokens);
    }

    @Override
    public long estimate(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) {
            total += estimate(message);
        }
        return total;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容表意
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
