package com.forgemind.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.ConfigException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * M9.5.2.1：LlmConfigResolver 字段级优先级合并。
 *
 * <p>优先级：CLI &gt; ENV &gt; 显式 --config &gt; Provider 默认值；
 * apiKey：CLI &gt; FORGEMIND_API_KEY &gt; 显式 --config &gt; null。</p>
 */
class LlmConfigResolverTest {

    private static final LlmConfigResolver.CliOverrides NO_CLI =
            new LlmConfigResolver.CliOverrides(null, null, null, null);
    private static final LlmConfigResolver.EnvOverrides NO_ENV =
            new LlmConfigResolver.EnvOverrides(null, null, null, null);

    private LlmConfig resolve(LlmConfigResolver.CliOverrides cli,
                              LlmConfigResolver.EnvOverrides env,
                              LlmConfig explicit) {
        return LlmConfigResolver.resolve(cli, env, explicit, null, null, null);
    }

    // ---------- 默认配置 ----------

    @Test
    void defaultsToOpenAiProvider() {
        LlmConfig cfg = resolve(NO_CLI, NO_ENV, null);
        assertEquals("https://api.openai.com/v1", cfg.baseUrl());
        assertEquals("gpt-4o-mini", cfg.model());
        assertNull(cfg.apiKey());
    }

    // ---------- Provider 映射 ----------

    @Test
    void deepseekProviderMapsDefaults() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("deepseek", null, null, null),
                NO_ENV, null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void openaiProviderMapsDefaults() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("openai", null, null, null),
                NO_ENV, null);
        assertEquals("https://api.openai.com/v1", cfg.baseUrl());
        assertEquals("gpt-4o-mini", cfg.model());
    }

    @Test
    void customProviderRequiresBaseUrlAndModel() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("custom", null,
                        "https://my-llm.example/v1", "my-model"),
                NO_ENV, null);
        assertEquals("https://my-llm.example/v1", cfg.baseUrl());
        assertEquals("my-model", cfg.model());
    }

    @Test
    void customProviderMissingBaseUrlFails() {
        ConfigException e = assertThrows(ConfigException.class, () -> resolve(
                new LlmConfigResolver.CliOverrides("custom", null, null, "m"),
                NO_ENV, null));
        assertEquals("custom provider requires --base-url (or FORGEMIND_BASE_URL)", e.getMessage());
    }

    @Test
    void customProviderMissingModelFails() {
        ConfigException e = assertThrows(ConfigException.class, () -> resolve(
                new LlmConfigResolver.CliOverrides("custom", null, "https://x/v1", null),
                NO_ENV, null));
        assertEquals("custom provider requires --model (or FORGEMIND_MODEL)", e.getMessage());
    }

    @Test
    void unknownProviderFails() {
        ConfigException e = assertThrows(ConfigException.class, () -> resolve(
                new LlmConfigResolver.CliOverrides("claude", null, null, null),
                NO_ENV, null));
        assertEquals("unknown provider: 'claude' (expected openai|deepseek|custom)", e.getMessage());
    }

    // ---------- CLI 覆盖 ----------

    @Test
    void cliBaseUrlOverridesProviderDefault() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("deepseek", null,
                        "https://proxy.example/v1", null),
                NO_ENV, null);
        assertEquals("https://proxy.example/v1", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model(), "provider 默认 model 应保留");
    }

    @Test
    void cliModelOverridesProviderDefault() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("deepseek", null, null, "my-model"),
                NO_ENV, null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals("my-model", cfg.model());
    }

    @Test
    void cliApiKeyWins() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("deepseek", "cli-key", null, null),
                new LlmConfigResolver.EnvOverrides(null, "env-key", null, null),
                new LlmConfig("https://cfg/v1", "cfg-key", "cfg-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertEquals("cli-key", cfg.apiKey());
    }

    // ---------- 环境变量 ----------

    @Test
    void envProviderWorks() {
        LlmConfig cfg = resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides("deepseek", null, null, null),
                null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void envApiKeyWorks() {
        LlmConfig cfg = resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides(null, "env-key", null, null),
                null);
        assertEquals("env-key", cfg.apiKey());
    }

    @Test
    void envBeatsExplicitConfig() {
        LlmConfig cfg = resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides(null, null, "https://env/v1", "env-model"),
                new LlmConfig("https://cfg/v1", "cfg-key", "cfg-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertEquals("https://env/v1", cfg.baseUrl());
        assertEquals("env-model", cfg.model());
        assertEquals("cfg-key", cfg.apiKey(), "env 未提供 apiKey 时保留 config 值");
    }

    // ---------- 显式 --config 保留 ----------

    @Test
    void explicitConfigFieldsPreservedWhenNoHigherPriority() {
        LlmConfig cfg = resolve(NO_CLI, NO_ENV,
                new LlmConfig("https://cfg/v1", "cfg-key", "cfg-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertEquals("https://cfg/v1", cfg.baseUrl());
        assertEquals("cfg-model", cfg.model());
        assertEquals("cfg-key", cfg.apiKey());
    }

    // ---------- 完整优先级链 ----------

    @Test
    void cliBeatsEnvBeatsConfigBeatsDefault() {
        LlmConfig cfg = resolve(
                new LlmConfigResolver.CliOverrides("openai", "cli-key", "https://cli/v1", "cli-model"),
                new LlmConfigResolver.EnvOverrides("deepseek", "env-key", "https://env/v1", "env-model"),
                new LlmConfig("https://cfg/v1", "cfg-key", "cfg-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertEquals("https://cli/v1", cfg.baseUrl());
        assertEquals("cli-model", cfg.model());
        assertEquals("cli-key", cfg.apiKey());
    }

    @Test
    void providerDefaultFillsWhenNoExplicitValue() {
        LlmConfig cfg = resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides("deepseek", null, null, null),
                null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl(), "provider 默认 baseUrl");
        assertEquals("deepseek-chat", cfg.model(), "provider 默认 model");
    }

    // ---------- API Key 缺失 ----------

    @Test
    void apiKeyMissingYieldsNull() {
        LlmConfig cfg = resolve(NO_CLI, NO_ENV, null);
        assertNull(cfg.apiKey(), "未提供 API Key 时 apiKey 为 null（交由 validateLlm 报错）");
    }

    // ---------- M9.5.2.2：用户级配置 ----------

    private static final UserConfigStore.UserConfig DEEPSEEK_USER =
            new UserConfigStore.UserConfig("deepseek", "user-key",
                    "https://api.deepseek.com/v1", "deepseek-chat", null, null);

    @Test
    void userConfigProviderWorks() {
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI, NO_ENV, null, DEEPSEEK_USER, null, null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model());
        assertEquals("user-key", cfg.apiKey());
    }

    @Test
    void userConfigOpenAiProvider() {
        UserConfigStore.UserConfig user = new UserConfigStore.UserConfig(
                "openai", null, null, null, null, null);
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI, NO_ENV, null, user, null, null);
        assertEquals("https://api.openai.com/v1", cfg.baseUrl());
        assertEquals("gpt-4o-mini", cfg.model());
    }

    @Test
    void userConfigCustomRequiresBaseUrlAndModel() {
        UserConfigStore.UserConfig user = new UserConfigStore.UserConfig(
                "custom", null, "https://custom/v1", "custom-model", null, null);
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI, NO_ENV, null, user, null, null);
        assertEquals("https://custom/v1", cfg.baseUrl());
        assertEquals("custom-model", cfg.model());
    }

    @Test
    void userConfigCustomMissingBaseUrlFails() {
        UserConfigStore.UserConfig user = new UserConfigStore.UserConfig(
                "custom", null, null, "m", null, null);
        assertThrows(ConfigException.class,
                () -> LlmConfigResolver.resolve(NO_CLI, NO_ENV, null, user, null, null));
    }

    @Test
    void userConfigCustomMissingModelFails() {
        UserConfigStore.UserConfig user = new UserConfigStore.UserConfig(
                "custom", null, "https://x/v1", null, null, null);
        assertThrows(ConfigException.class,
                () -> LlmConfigResolver.resolve(NO_CLI, NO_ENV, null, user, null, null));
    }

    @Test
    void cliProviderKeepsUserExplicitBaseUrlAndModel() {
        // 字段级合并：用户配置显式保存了 baseUrl/model（独立字段），CLI 只改 provider
        // → baseUrl/model 仍用用户显式值，provider 默认不覆盖已存在的显式字段。
        LlmConfig cfg = LlmConfigResolver.resolve(
                new LlmConfigResolver.CliOverrides("openai", null, null, null),
                NO_ENV, null, DEEPSEEK_USER, null, null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl(),
                "用户显式 baseUrl 不被 CLI provider 覆盖");
        assertEquals("deepseek-chat", cfg.model(), "用户显式 model 不被 CLI provider 覆盖");
        assertEquals("user-key", cfg.apiKey());
    }

    @Test
    void cliProviderFillsDefaultsWhenUserFieldsAbsent() {
        // 用户配置只有 provider=deepseek（无显式 baseUrl/model）→ CLI provider=openai 时
        // baseUrl/model 走 openai 默认。
        UserConfigStore.UserConfig bareProvider = new UserConfigStore.UserConfig(
                "deepseek", null, null, null, null, null);
        LlmConfig cfg = LlmConfigResolver.resolve(
                new LlmConfigResolver.CliOverrides("openai", null, null, null),
                NO_ENV, null, bareProvider, null, null);
        assertEquals("https://api.openai.com/v1", cfg.baseUrl(),
                "无显式 baseUrl 时 CLI provider 决定默认值");
        assertEquals("gpt-4o-mini", cfg.model());
    }

    @Test
    void cliApiKeyBeatsUserConfig() {
        LlmConfig cfg = LlmConfigResolver.resolve(
                new LlmConfigResolver.CliOverrides(null, "cli-key", null, null),
                NO_ENV, null, DEEPSEEK_USER, null, null);
        assertEquals("cli-key", cfg.apiKey());
    }

    @Test
    void envProviderFillsDefaultsWhenUserFieldsAbsent() {
        UserConfigStore.UserConfig bareProvider = new UserConfigStore.UserConfig(
                "deepseek", null, null, null, null, null);
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides("openai", null, null, null),
                null, bareProvider, null, null);
        assertEquals("https://api.openai.com/v1", cfg.baseUrl(), "ENV provider 决定默认值");
        assertEquals("gpt-4o-mini", cfg.model());
    }

    @Test
    void envApiKeyBeatsUserConfig() {
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides(null, "env-key", null, null),
                null, DEEPSEEK_USER, null, null);
        assertEquals("env-key", cfg.apiKey());
    }

    @Test
    void userConfigBeatsExplicitConfig() {
        LlmConfig cfg = LlmConfigResolver.resolve(NO_CLI, NO_ENV,
                new LlmConfig("https://cfg/v1", "cfg-key", "cfg-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)),
                DEEPSEEK_USER, null, null);
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl(), "用户配置覆盖显式 --config");
        assertEquals("user-key", cfg.apiKey());
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void apiKeyPriorityCliOverEnvOverUser() {
        LlmConfig cfg = LlmConfigResolver.resolve(
                new LlmConfigResolver.CliOverrides(null, "cli-key", null, null),
                new LlmConfigResolver.EnvOverrides(null, "env-key", null, null),
                null, DEEPSEEK_USER, null, null);
        assertEquals("cli-key", cfg.apiKey());
        LlmConfig cfg2 = LlmConfigResolver.resolve(NO_CLI,
                new LlmConfigResolver.EnvOverrides(null, "env-key", null, null),
                null, DEEPSEEK_USER, null, null);
        assertEquals("env-key", cfg2.apiKey());
    }
}
