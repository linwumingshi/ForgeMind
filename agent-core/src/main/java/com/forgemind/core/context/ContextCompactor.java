package com.forgemind.core.context;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import java.util.List;

/**
 * 粗粒度上下文压缩器（不引入 tokenizer）。
 *
 * <p>消息按组划分：{@code ASSISTANT(tool_calls)} 与其后连续的 {@code TOOL} 消息
 * 构成一个<b>不可拆分</b>的组（保证 tool_call_id 关联不孤裂）；其余消息（SYSTEM /
 * USER / ASSISTANT(final)）各自为单条组。</p>
 *
 * <p>保护规则：index 0（SYSTEM）与最后一个组永不删除；仅当总字符数超过
 * {@code maxChars} 时，从最旧的未受保护组开始整组删除；若只剩受保护组仍超预算，
 * 停止删除（允许超预算，不抛异常）。{@code maxChars <= 0} 表示禁用。</p>
 */
public final class ContextCompactor {

    private ContextCompactor() {
    }

    /** 原地压缩消息列表。 */
    public static void compact(List<ChatMessage> messages, long maxChars) {
        if (maxChars <= 0 || messages == null || messages.size() <= 2) {
            return;
        }
        while (totalChars(messages) > maxChars) {
            int lastGroupStart = findLastGroupStart(messages);
            int removable = findOldestRemovableGroupStart(messages, lastGroupStart);
            if (removable < 0) {
                return; // 只剩受保护组
            }
            removeGroup(messages, removable);
            if (messages.size() <= 2) {
                return; // system + 最后一组，即使超预算也不再删
            }
        }
    }

    /** 粗略字符预算（content + tool_calls 元数据）。 */
    static long totalChars(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            if (m.content() != null) {
                total += m.content().length();
            }
            if (m.toolCalls() != null) {
                for (com.forgemind.model.ToolCall call : m.toolCalls()) {
                    total += (call.id() == null ? 0 : call.id().length())
                            + (call.name() == null ? 0 : call.name().length());
                }
            }
        }
        return total;
    }

    /** 最后一个组的起始索引（若最后一条是 TOOL，回退到其所属 assistant(tool_calls)）。 */
    private static int findLastGroupStart(List<ChatMessage> messages) {
        int last = messages.size() - 1;
        if (messages.get(last).role() == Role.TOOL) {
            for (int i = last; i >= 1; i--) {
                ChatMessage m = messages.get(i);
                if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    return i;
                }
            }
            return 1;
        }
        return last;
    }

    /** 可删区域内（index 1 .. lastGroupStart 之前）最旧组的起始索引；无则 -1。 */
    private static int findOldestRemovableGroupStart(List<ChatMessage> messages, int lastGroupStart) {
        if (lastGroupStart <= 1) {
            return -1;
        }
        for (int i = 1; i < lastGroupStart; i++) {
            // TOOL 消息属于前一组，跳过（组起点是 ASSISTANT(tool_calls)/USER/ASSISTANT(content)）
            if (messages.get(i).role() != Role.TOOL) {
                return i;
            }
        }
        return -1;
    }

    /** 删除从 start 起的整组（ASSISTANT(tool_calls) 吸收其后连续 TOOL）。 */
    private static void removeGroup(List<ChatMessage> messages, int start) {
        int end = start;
        ChatMessage first = messages.get(start);
        if (first.role() == Role.ASSISTANT
                && first.toolCalls() != null
                && !first.toolCalls().isEmpty()) {
            while (end + 1 < messages.size() && messages.get(end + 1).role() == Role.TOOL) {
                end++;
            }
        }
        messages.subList(start, end + 1).clear();
    }
}
