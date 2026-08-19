package com.forgemind.cli.config;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.util.List;

/**
 * LLM 连通性探针（M9.5.2.3）：使用现有 {@link LlmClient#chat} 发送最小请求，
 * 不执行 AgentLoop、不执行任何工具、不修改任何文件。
 *
 * <p>安全：返回的失败原因经 {@link #sanitize} 过滤，<b>绝不包含 API Key /
 * Authorization header / Bearer</b>；异常消息由客户端层已脱敏，此处做二次防御。</p>
 */
public final class LlmConnectivityProbe {

    private LlmConnectivityProbe() {
    }

    /**
     * 探测连通性。
     *
     * @param client 现有 LlmClient（真实或 mock）
     * @param llm    配置（用于 key 二次过滤）
     * @return null = 通过；否则为脱敏后的失败结果
     */
    public static ConfigReporter.ConnectivityResult probe(LlmClient client, LlmConfig llm) {
        if (client == null) {
            return ConfigReporter.ConnectivityResult.failure("LLM client is not initialized");
        }
        try {
            AgentResponse response = client.chat(List.of(ChatMessage.user("ping")));
            if (response == null) {
                return ConfigReporter.ConnectivityResult.failure("LLM returned no response");
            }
            return null; // 通过
        } catch (LlmException e) {
            String message = sanitize(e.getMessage() == null ? "unknown LLM error" : e.getMessage(), llm);
            return classify(message);
        } catch (RuntimeException e) {
            String message = sanitize(e.getMessage() == null ? e.toString() : e.getMessage(), llm);
            return ConfigReporter.ConnectivityResult.failure(message);
        }
    }

    /** 从异常消息提取分类（HTTP 状态 / 网络失败），消息本身已由客户端脱敏。 */
    private static ConfigReporter.ConnectivityResult classify(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("HTTP\\s+(\\d{3})").matcher(message);
        if (m.find()) {
            int status = Integer.parseInt(m.group(1));
            String reason = switch (status) {
                case 401, 403 -> "Unauthorized / forbidden (check API key)";
                case 404 -> "endpoint not found (check base URL)";
                case 429 -> "rate limited";
                default -> "HTTP error " + status;
            };
            return ConfigReporter.ConnectivityResult.httpFailure(status, reason);
        }
        if (message.toLowerCase().contains("timed out") || message.toLowerCase().contains("timeout")) {
            return ConfigReporter.ConnectivityResult.failure("timeout");
        }
        if (message.toLowerCase().contains("connect") || message.toLowerCase().contains("refused")
                || message.toLowerCase().contains("failed to connect")) {
            return ConfigReporter.ConnectivityResult.failure("connection failed: " + message);
        }
        return ConfigReporter.ConnectivityResult.failure(message);
    }

    /** 二次防御：从错误消息中剔除任何 Key 值片段。 */
    public static String sanitize(String message, LlmConfig llm) {
        String result = message;
        if (llm != null && llm.apiKey() != null && !llm.apiKey().isBlank()) {
            result = result.replace(llm.apiKey(), "***");
        }
        return result;
    }
}
