package com.forgemind.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/**
 * M9.5.2.2：ConfigWizard —— 交互输入收集、provider 默认、custom 必填、
 * API Key 不回显（console 优先 + fallback）。
 */
class ConfigWizardTest {

    /** 假输入队列 + 捕获输出。 */
    private static final class FakeIo implements ConfigWizard.Input, ConfigWizard.Output {
        final Queue<String> lines = new ArrayDeque<>();
        final List<String> printed = new ArrayList<>();

        FakeIo(String... inputs) {
            for (String s : inputs) {
                lines.add(s);
            }
        }

        @Override
        public String readLine() {
            return lines.isEmpty() ? null : lines.poll();
        }

        @Override
        public void println(String line) {
            printed.add(line);
        }

        @Override
        public void print(String line) {
            printed.add(line);
        }
    }

    /** console=null 路径：走 Scanner fallback（测试默认 System.console() 为 null）。 */
    private static ConfigWizard wizard(FakeIo io) {
        return new ConfigWizard(io, io, null); // 显式 console=null → 强制 fallback
    }

    @Test
    void deepseekDefaultsWhenBlank() {
        FakeIo io = new FakeIo("", "test-key", "", "");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertEquals("deepseek", cfg.provider());
        assertEquals("test-key", cfg.apiKey());
        assertEquals("https://api.deepseek.com", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void openaiProviderUsesOpenAiDefaults() {
        FakeIo io = new FakeIo("openai", "test-key", "", "");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertEquals("openai", cfg.provider());
        assertEquals("https://api.openai.com/v1", cfg.baseUrl());
        assertEquals("gpt-4o-mini", cfg.model());
    }

    @Test
    void customRequiresExplicitBaseUrlAndModel() {
        FakeIo io = new FakeIo("custom", "test-key", "https://my-llm/v1", "my-model");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertEquals("custom", cfg.provider());
        assertEquals("https://my-llm/v1", cfg.baseUrl());
        assertEquals("my-model", cfg.model());
    }

    @Test
    void customMissingBaseUrlNotSaved() {
        FakeIo io = new FakeIo("custom", "test-key", "", "my-model");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertNull(cfg.baseUrl(), "custom 缺 baseUrl 不应产出可保存配置");
        assertTrue(io.printed.stream().anyMatch(s -> s.contains("requires a base URL")));
    }

    @Test
    void customMissingModelNotSaved() {
        FakeIo io = new FakeIo("custom", "test-key", "https://x/v1", "");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertNull(cfg.model(), "custom 缺 model 不应产出可保存配置");
    }

    @Test
    void apiKeyNeverPrintedInOutput() {
        FakeIo io = new FakeIo("deepseek", "sk-test-secret", "", "");
        wizard(io).run(null);
        assertFalse(io.printed.stream().anyMatch(s -> s.contains("sk-test-secret")),
                "向导输出不得包含用户输入的 API Key");
    }

    @Test
    void blankApiKeyKeepsExisting() {
        UserConfigStore.UserConfig existing = new UserConfigStore.UserConfig(
                "deepseek", "existing-key", "https://api.deepseek.com", "deepseek-chat", null, null);
        FakeIo io = new FakeIo("", "", "", ""); // 全部回车 = 用默认/现有
        UserConfigStore.UserConfig cfg = wizard(io).run(existing);
        assertEquals("existing-key", cfg.apiKey(), "空 Key 输入应保留现有 Key");
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void unknownProviderNotSaved() {
        FakeIo io = new FakeIo("claude", "test-key", "", "");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertNull(cfg.provider(), "非法 provider 不应产出配置");
        assertTrue(io.printed.stream().anyMatch(s -> s.contains("Unknown provider")));
    }

    @Test
    void consoleFallbackPathWorksWithoutNpe() {
        // console=null（IDE/管道/测试）→ 走 Scanner fallback，不崩溃
        FakeIo io = new FakeIo("deepseek", "test-key", "", "");
        UserConfigStore.UserConfig cfg = wizard(io).run(null);
        assertEquals("deepseek", cfg.provider());
        assertEquals("test-key", cfg.apiKey());
        assertTrue(io.printed.stream().anyMatch(s -> s.contains("will not be hidden")),
                "console 不可用时应提示输入不会隐藏");
    }

    @Test
    void eofInputCancelsWizard() {
        // 输入流为空（管道/EOF）→ 向导取消（返回 null），调用方不保存
        FakeIo io = new FakeIo();
        assertNull(wizard(io).run(null), "EOF 应取消向导，不产出配置");
    }
}
