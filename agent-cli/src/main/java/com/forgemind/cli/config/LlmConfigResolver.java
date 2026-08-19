package com.forgemind.cli.config;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.ConfigException;
import java.time.Duration;

/**
 * LLM 配置合并器（M9.5，纯函数、无 IO、可单测）。
 *
 * <p>按<b>字段级优先级</b>合并多个配置来源，每字段独立取最高优先级非空值：</p>
 *
 * <pre>
 * provider: CLI &gt; ENV &gt; 用户级配置 &gt; (显式 --config 无 provider 字段) &gt; 默认 openai
 * baseUrl : CLI &gt; ENV &gt; 用户级配置 &gt; 显式 --config &gt; provider 默认
 * model   : CLI &gt; ENV &gt; 用户级配置 &gt; 显式 --config &gt; provider 默认
 * apiKey  : CLI &gt; FORGEMIND_API_KEY &gt; 用户级配置 &gt; 显式 --config &gt; null
 * </pre>
 *
 * <p>兼容保证：若 {@code explicitConfig} 的某字段非空（如测试注入的完整
 * {@link LlmConfig}），且 CLI/ENV/用户配置未覆盖，则原值保留 —— 不破坏现有
 * {@code --config} 与既有测试语义。</p>
 */
public final class LlmConfigResolver {

    private LlmConfigResolver() {
    }

    /** CLI 覆盖项（全可空，来自 --provider/--api-key/--base-url/--model）。 */
    public record CliOverrides(String provider, String apiKey, String baseUrl, String model) {
    }

    /** 环境变量覆盖项（全可空：FORGEMIND_PROVIDER/FORGEMIND_API_KEY/...）。 */
    public record EnvOverrides(String provider, String apiKey, String baseUrl, String model) {
    }

    /**
     * 合并并校验最终 LLM 配置。
     *
     * @param cli             CLI 覆盖（非 null）
     * @param env             环境变量覆盖（非 null）
     * @param explicitConfig  显式 --config 的 llm 节（可 null = 未提供）
     * @param userConfig      用户级配置（可 null = 无 ~/.forgemind/config.yml）
     * @param connectTimeout  连接超时（null = LlmConfig 默认）
     * @param readTimeout     读取超时（null = LlmConfig 默认）
     */
    public static LlmConfig resolve(CliOverrides cli, EnvOverrides env,
                                    LlmConfig explicitConfig,
                                    UserConfigStore.UserConfig userConfig,
                                    Duration connectTimeout, Duration readTimeout) {
        String userProvider = userConfig == null ? null : userConfig.provider();
        // provider 优先级：CLI > ENV > 用户配置 > 默认 openai
        LlmProvider provider = cli.provider() != null
                ? LlmProvider.parse(cli.provider())
                : env.provider() != null
                        ? LlmProvider.parse(env.provider())
                        : userProvider != null
                                ? LlmProvider.parse(userProvider)
                                : LlmProvider.OPENAI;
        String baseUrl = firstNonNull(
                cli.baseUrl, env.baseUrl,
                userConfig == null ? null : userConfig.baseUrl(),
                explicitConfig == null ? null : explicitConfig.baseUrl(),
                provider.defaultBaseUrl());
        String model = firstNonNull(
                cli.model, env.model,
                userConfig == null ? null : userConfig.model(),
                explicitConfig == null ? null : explicitConfig.model(),
                provider.defaultModel());
        String apiKey = firstNonNull(
                cli.apiKey, env.apiKey,
                userConfig == null ? null : userConfig.apiKey(),
                explicitConfig == null ? null : explicitConfig.apiKey());

        if (provider == LlmProvider.CUSTOM) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new ConfigException("custom provider requires --base-url (or FORGEMIND_BASE_URL)");
            }
            if (model == null || model.isBlank()) {
                throw new ConfigException("custom provider requires --model (or FORGEMIND_MODEL)");
            }
        }

        return new LlmConfig(
                baseUrl,
                apiKey,
                model,
                connectTimeout != null ? connectTimeout : LlmConfig.DEFAULT_CONNECT_TIMEOUT,
                readTimeout != null ? readTimeout : LlmConfig.DEFAULT_READ_TIMEOUT);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
