package com.forgemind.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.exception.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.5.2.2：UserConfigStore —— 路径、保存、读取、目录创建、原子写入。
 */
class UserConfigStoreTest {

    @TempDir
    Path tempDir;

    private UserConfigStore store() {
        return new UserConfigStore(tempDir.resolve(".forgemind/config.yml"));
    }

    @Test
    void defaultPathIsUserHomeDotForgeMind() {
        Path path = UserConfigStore.defaultConfigPath();
        String home = System.getProperty("user.home");
        assertTrue(path.startsWith(Path.of(home)));
        assertTrue(path.endsWith(Path.of(".forgemind", "config.yml")));
    }

    @Test
    void missingFileLoadsEmpty() {
        UserConfigStore.UserConfig cfg = store().load();
        assertNull(cfg.provider());
        assertNull(cfg.apiKey());
        assertFalse(store().exists());
    }

    @Test
    void saveCreatesDirectoryAndFile() {
        UserConfigStore store = store();
        assertFalse(store.exists());
        store.save(new UserConfigStore.UserConfig("deepseek", "test-key",
                "https://api.deepseek.com/v1", "deepseek-chat",
                Duration.ofSeconds(10), Duration.ofSeconds(60)));
        assertTrue(store.exists(), "保存应自动创建配置目录与文件");
        assertTrue(Files.exists(tempDir.resolve(".forgemind/config.yml")));
    }

    @Test
    void saveThenLoadRoundTrips() {
        UserConfigStore store = store();
        store.save(new UserConfigStore.UserConfig("openai", "dummy-key",
                "https://api.openai.com/v1", "gpt-4o-mini",
                Duration.ofSeconds(10), Duration.ofSeconds(60)));
        UserConfigStore.UserConfig loaded = store.load();
        assertEquals("openai", loaded.provider());
        assertEquals("dummy-key", loaded.apiKey());
        assertEquals("https://api.openai.com/v1", loaded.baseUrl());
        assertEquals("gpt-4o-mini", loaded.model());
        assertEquals(Duration.ofSeconds(10), loaded.connectTimeout());
        assertEquals(Duration.ofSeconds(60), loaded.readTimeout());
    }

    @Test
    void yamlParseHandlesPartialFields() throws IOException {
        Files.createDirectories(tempDir.resolve(".forgemind"));
        Files.writeString(tempDir.resolve(".forgemind/config.yml"), """
                provider: deepseek
                baseUrl: https://api.deepseek.com/v1
                model: deepseek-chat
                """, StandardCharsets.UTF_8);
        UserConfigStore.UserConfig loaded = store().load();
        assertEquals("deepseek", loaded.provider());
        assertEquals("https://api.deepseek.com/v1", loaded.baseUrl());
        assertNull(loaded.apiKey(), "缺省 apiKey 应为 null");
    }

    @Test
    void envPlaceholderIsExpandedOnLoad() throws IOException {
        Files.createDirectories(tempDir.resolve(".forgemind"));
        Files.writeString(tempDir.resolve(".forgemind/config.yml"), """
                provider: deepseek
                apiKey: ${FORGEMIND_API_KEY}
                baseUrl: https://api.deepseek.com/v1
                model: deepseek-chat
                """, StandardCharsets.UTF_8);
        UserConfigStore store = new UserConfigStore(tempDir.resolve(".forgemind/config.yml"));
        UserConfigStore.UserConfig loaded = store.load(java.util.Map.of("FORGEMIND_API_KEY", "env-secret"));
        assertEquals("env-secret", loaded.apiKey(), "${VAR} 占位符应被展开");
    }

    @Test
    void invalidYamlFailsClearly() throws IOException {
        Files.createDirectories(tempDir.resolve(".forgemind"));
        Files.writeString(tempDir.resolve(".forgemind/config.yml"),
                "provider: [unclosed", StandardCharsets.UTF_8);
        ConfigException e = org.junit.jupiter.api.Assertions.assertThrows(ConfigException.class,
                () -> store().load());
        assertTrue(e.getMessage().contains("invalid user config"));
    }

    @Test
    void isCompleteDetectsFullConfig() {
        UserConfigStore.UserConfig full = new UserConfigStore.UserConfig(
                "deepseek", "test-key", "https://api.deepseek.com/v1", "deepseek-chat",
                null, null);
        assertTrue(full.isComplete());
        assertFalse(new UserConfigStore.UserConfig("deepseek", null,
                "https://api.deepseek.com/v1", "deepseek-chat", null, null).isComplete());
    }

    @Test
    void apiKeyNeverAppearsInStoreToYamlOutputPath() throws IOException {
        // toYaml 是包私有：验证保存后的文件内容包含 Key（用户级文件允许），
        // 但 store 的任何错误/路径信息不得包含 Key。
        UserConfigStore store = store();
        store.save(new UserConfigStore.UserConfig("deepseek", "test-key",
                "https://api.deepseek.com/v1", "deepseek-chat", null, null));
        String content = new String(Files.readAllBytes(store.configPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("test-key"), "用户级文件允许保存 Key（本阶段设计）");
        assertFalse(store.configPath().toString().contains("test-key"));
    }
}
