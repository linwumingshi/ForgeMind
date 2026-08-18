package com.forgemind.core.config;

import com.forgemind.core.exception.ConfigException;
import java.time.Duration;
import java.util.Objects;

/**
 * LLM 接入配置（OpenAI-Compatible）。
 *
 * <p>{@code apiKey} 与 {@code model} 允许为 null/空——由 CLI 启动阶段进行明确校验
 * （缺失时给出不含 Key 值的明确错误）。本类内部不打印任何敏感信息。</p>
 *
 * @param baseUrl         API 基础地址（默认 OpenAI 官方，可经配置覆盖为 DeepSeek 等）
 * @param apiKey          Bearer Token（推荐经环境变量 FORGEMIND_API_KEY 注入）
 * @param model           模型名，如 deepseek-chat / gpt-4o-mini
 * @param connectTimeout  连接超时（默认 10s）
 * @param readTimeout     读取超时（默认 60s）
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration readTimeout) {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    public LlmConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.isBlank()) {
            throw new ConfigException("baseUrl must not be blank");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new ConfigException("connectTimeout must be positive: " + connectTimeout);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new ConfigException("readTimeout must be positive: " + readTimeout);
        }
    }

    public static LlmConfig defaults() {
        return new LlmConfig(DEFAULT_BASE_URL, null, null,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public LlmConfig withBaseUrl(String value) {
        return new LlmConfig(value, apiKey, model, connectTimeout, readTimeout);
    }

    public LlmConfig withApiKey(String value) {
        return new LlmConfig(baseUrl, value, model, connectTimeout, readTimeout);
    }

    public LlmConfig withModel(String value) {
        return new LlmConfig(baseUrl, apiKey, value, connectTimeout, readTimeout);
    }

    public LlmConfig withConnectTimeout(Duration value) {
        return new LlmConfig(baseUrl, apiKey, model, value, readTimeout);
    }

    public LlmConfig withReadTimeout(Duration value) {
        return new LlmConfig(baseUrl, apiKey, model, connectTimeout, value);
    }

    /**
     * 脱敏 toString：apiKey 永不出现（record 默认 toString 会输出明文，覆盖防御）。
     */
    @Override
    public String toString() {
        return "LlmConfig[baseUrl=" + baseUrl
                + ", apiKey=***"
                + ", model=" + model
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
