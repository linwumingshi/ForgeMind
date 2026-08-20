package com.forgemind.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forgemind.core.exception.ConfigException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmConfigTest {

    @Test
    void defaultsAreSane() {
        LlmConfig config = LlmConfig.defaults();
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertNull(config.apiKey());
        assertNull(config.model());
        assertEquals(Duration.ofSeconds(10), config.connectTimeout());
        assertEquals(Duration.ofSeconds(60), config.readTimeout());
    }

    @Test
    void rejectsBlankBaseUrl() {
        assertThrows(ConfigException.class,
                () -> new LlmConfig("  ", "k", "m", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new LlmConfig(null, "k", "m", Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(ConfigException.class,
                () -> new LlmConfig("http://x", "k", "m", Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(ConfigException.class,
                () -> new LlmConfig("http://x", "k", "m", Duration.ofSeconds(1), Duration.ofSeconds(-5)));
    }

    @Test
    void withersAreImmutable() {
        LlmConfig base = LlmConfig.defaults();
        LlmConfig changed = base.withApiKey("secret-key").withModel("deepseek-chat")
                .withBaseUrl("https://api.deepseek.com");
        assertNull(base.apiKey());
        assertEquals("secret-key", changed.apiKey());
        assertEquals("deepseek-chat", changed.model());
        assertEquals("https://api.deepseek.com", changed.baseUrl());
    }
}
