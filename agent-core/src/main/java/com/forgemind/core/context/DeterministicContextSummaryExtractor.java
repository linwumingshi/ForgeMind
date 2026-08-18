package com.forgemind.core.context;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.ContextSummary;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 确定性上下文摘要提取器（<b>不调用 LLM</b>）。
 *
 * <p>基于已有消息确定性提取：task（首条 USER）、modifiedFiles（write_file/edit_file
 * 的 path 参数）、commands（shell 的 command 参数）、testResults（TOOL 消息中的
 * "Tests run:" / "BUILD SUCCESS|FAILURE" 行）。facts / pendingWork / decisions
 * 无法可靠确定，保持为空（不编造事实）。</p>
 */
public final class DeterministicContextSummaryExtractor {

    private static final int TASK_MAX_LENGTH = 300;
    private static final int ITEM_MAX_LENGTH = 200;
    private static final int MAX_TEST_RESULTS = 5;

    private DeterministicContextSummaryExtractor() {
    }

    public static ContextSummary extract(List<ChatMessage> messages) {
        String task = null;
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Set<String> commands = new LinkedHashSet<>();
        Set<String> testResults = new LinkedHashSet<>();

        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message.role() == Role.USER && task == null) {
                    task = truncate(message.content(), TASK_MAX_LENGTH);
                }
                if (message.role() == Role.ASSISTANT && message.toolCalls() != null) {
                    for (ToolCall call : message.toolCalls()) {
                        Object path = call.arguments().get("path");
                        if ((call.name().equals("write_file") || call.name().equals("edit_file"))
                                && path instanceof String p && !p.isBlank()) {
                            modifiedFiles.add(truncate(p, ITEM_MAX_LENGTH));
                        }
                        if (call.name().equals("shell")) {
                            Object command = call.arguments().get("command");
                            if (command instanceof String s && !s.isBlank()) {
                                commands.add(truncate(s, ITEM_MAX_LENGTH));
                            }
                        }
                    }
                }
                if (message.role() == Role.TOOL && message.content() != null) {
                    extractTestResults(message.content(), testResults);
                }
            }
        }

        return new ContextSummary(
                task == null ? "" : task,
                List.of(),
                List.copyOf(modifiedFiles),
                List.copyOf(commands),
                List.copyOf(testResults),
                List.of(),
                List.of());
    }

    private static void extractTestResults(String content, Set<String> out) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("Tests run:") || trimmed.startsWith("BUILD SUCCESS")
                    || trimmed.startsWith("BUILD FAILURE")) {
                out.add(truncate(trimmed, ITEM_MAX_LENGTH));
                if (out.size() >= MAX_TEST_RESULTS) {
                    return;
                }
            }
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
