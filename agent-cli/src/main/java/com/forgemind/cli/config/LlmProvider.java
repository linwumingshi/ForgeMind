package com.forgemind.cli.config;

import com.forgemind.core.exception.ConfigException;
import java.util.Locale;

/**
 * LLM Provider（M9.5，仅配置层概念，不进入 agent-core）。
 *
 * <p>Provider 只负责把"标识名"映射为 {@code baseUrl + model} 默认值，
 * 最终仍统一驱动 {@link com.forgemind.core.config.LlmConfig} →
 * {@link com.forgemind.llm.openai.OpenAiCompatibleLlmClient}，不引入任何
 * 新 LLM Framework。</p>
 *
 * <ul>
 *   <li>{@code openai}：baseUrl=https://api.openai.com/v1，model=gpt-4o-mini；</li>
 *   <li>{@code deepseek}：baseUrl=https://api.deepseek.com/v1，model=deepseek-chat；</li>
 *   <li>{@code custom}：baseUrl 与 model 必须由用户提供（默认值为 null）。</li>
 * </ul>
 */
public enum LlmProvider {

    OPENAI("https://api.openai.com/v1", "gpt-4o-mini"),
    DEEPSEEK("https://api.deepseek.com/v1", "deepseek-chat"),
    CUSTOM(null, null);

    private final String defaultBaseUrl;
    private final String defaultModel;

    LlmProvider(String defaultBaseUrl, String defaultModel) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    /** Provider 默认 baseUrl（custom 为 null）。 */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** Provider 默认 model（custom 为 null）。 */
    public String defaultModel() {
        return defaultModel;
    }

    /**
     * 解析 provider 名（大小写不敏感）。
     *
     * @throws ConfigException 未知 provider（错误信息不含任何敏感内容）
     */
    public static LlmProvider parse(String name) {
        if (name == null || name.isBlank()) {
            throw new ConfigException("provider must not be blank");
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(
                    "unknown provider: '" + name + "' (expected openai|deepseek|custom)");
        }
    }
}
