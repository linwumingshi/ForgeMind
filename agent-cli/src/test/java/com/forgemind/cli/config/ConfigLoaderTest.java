package com.forgemind.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void loadsFullConfig() throws IOException {
        Path file = write("""
                agent:
                  maxIterations: 20
                  toolLimits:
                    readFileMaxBytes: 2048
                    shellTimeout: PT30S
                llm:
                  baseUrl: https://api.deepseek.com/v1
                  apiKey: ${FORGEMIND_API_KEY}
                  model: deepseek-chat
                  connectTimeout: 5
                  readTimeout: PT90S
                """);
        ConfigLoader.Loaded loaded = ConfigLoader.load(file, Map.of("FORGEMIND_API_KEY", "env-secret"));
        assertEquals(20, loaded.agent().maxIterations());
        assertEquals(2048, loaded.agent().toolLimits().readFileMaxBytes());
        assertEquals(Duration.ofSeconds(30), loaded.agent().toolLimits().shellTimeout());
        assertEquals("https://api.deepseek.com/v1", loaded.llm().baseUrl());
        assertEquals("env-secret", loaded.llm().apiKey());
        assertEquals("deepseek-chat", loaded.llm().model());
        assertEquals(Duration.ofSeconds(5), loaded.llm().connectTimeout());
        assertEquals(Duration.ofSeconds(90), loaded.llm().readTimeout());
    }

    @Test
    void environmentVariableIsExpanded() throws IOException {
        Path file = write("""
                llm:
                  apiKey: ${FORGEMIND_API_KEY}
                """);
        ConfigLoader.Loaded loaded = ConfigLoader.load(file, Map.of("FORGEMIND_API_KEY", "abc123"));
        assertEquals("abc123", loaded.llm().apiKey());
    }

    @Test
    void missingEnvironmentVariableFailsClearly() throws IOException {
        Path file = write("""
                llm:
                  apiKey: ${FORGEMIND_API_KEY}
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, Map.of()));
        assertTrue(e.getMessage().contains("FORGEMIND_API_KEY"));
        assertTrue(e.getMessage().contains("is not set"));
    }

    @Test
    void invalidYamlFailsClearly() throws IOException {
        Path file = write("agent: [unclosed");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file, Map.of()));
        assertTrue(e.getMessage().contains("invalid YAML"));
    }

    @Test
    void missingFileFailsClearly() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(tempDir.resolve("nope.yml"), Map.of()));
        assertTrue(e.getMessage().contains("failed to read"));
    }

    @Test
    void emptyConfigUsesDefaults() throws IOException {
        Path file = write("llm:\n  apiKey: x\n");
        ConfigLoader.Loaded loaded = ConfigLoader.load(file, Map.of());
        assertEquals(com.forgemind.core.config.AgentConfig.DEFAULT_MAX_ITERATIONS,
                loaded.agent().maxIterations());
        assertEquals(com.forgemind.core.config.ToolLimits.defaults(),
                loaded.agent().toolLimits());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, loaded.llm().baseUrl());
    }

    @Test
    void partialToolLimitsUseDefaultsForMissingFields() throws IOException {
        Path file = write("""
                agent:
                  toolLimits:
                    listFilesMaxEntries: 100
                """);
        ConfigLoader.Loaded loaded = ConfigLoader.load(file, Map.of());
        assertEquals(100, loaded.agent().toolLimits().listFilesMaxEntries());
        assertEquals(3, loaded.agent().toolLimits().listFilesMaxDepth());
        assertEquals(64L * 1024, loaded.agent().toolLimits().outputLimit());
    }

    @Test
    void apiKeyMayBeAbsentFromFile() throws IOException {
        Path file = write("llm:\n  model: m\n");
        ConfigLoader.Loaded loaded = ConfigLoader.load(file, Map.of());
        assertNull(loaded.llm().apiKey());
        assertFalse(loaded.llm().apiKey() != null && loaded.llm().apiKey().contains("secret"));
    }
}
