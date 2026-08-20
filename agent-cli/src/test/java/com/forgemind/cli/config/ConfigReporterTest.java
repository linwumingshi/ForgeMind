package com.forgemind.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.5.2.3: ConfigReporter (--config-show / --doctor) and LlmConnectivityProbe redaction.
 */
class ConfigReporterTest {

    @TempDir
    Path tempDir;

    private static final String SECRET = "test-secret-key";

    private static final class Captured {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        final ConfigReporter reporter = new ConfigReporter(out);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static LlmConfig fullConfig() {
        return new LlmConfig("https://api.deepseek.com", SECRET, "deepseek-chat",
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    // ---------- config-show ----------

    @Test
    void showConfigDisplaysRedactedKey() {
        Captured c = new Captured();
        c.reporter.showConfig(fullConfig());
        String text = c.text();
        assertTrue(text.contains("Provider: deepseek"));
        assertTrue(text.contains("Base URL: https://api.deepseek.com"));
        assertTrue(text.contains("Model: deepseek-chat"));
        assertTrue(text.contains("API Key: configured"));
        assertFalse(text.contains(SECRET), "涓嶅緱杈撳嚭瀹屾暣 Key");
        assertFalse(text.contains("Bearer"));
        assertFalse(text.contains("Authorization"));
    }

    @Test
    void showConfigMissingKeySaysNotConfigured() {
        Captured c = new Captured();
        c.reporter.showConfig(new LlmConfig("https://api.deepseek.com", null, "m",
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(c.text().contains("API Key: not configured"));
    }

    // ---------- doctor ----------

    @Test
    void doctorAllGreen() {
        Captured c = new Captured();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        com.forgemind.cli.CliAssembly.standardTools().forEach(registry::register);
        boolean ok = c.reporter.doctor(fullConfig(), tempDir, registry,
                true, null); // connectivity null = 閫氳繃
        assertTrue(ok);
        String text = c.text();
        assertTrue(text.contains("[OK] Configuration"));
        assertTrue(text.contains("[OK] API Key"));
        assertTrue(text.contains("[OK] Base URL"));
        assertTrue(text.contains("[OK] Model"));
        assertTrue(text.contains("[OK] Working directory"));
        assertTrue(text.contains("[OK] Tool registry"));
        assertTrue(text.contains("[OK] Permission system"));
        assertTrue(text.contains("[OK] LLM connectivity"));
        assertFalse(text.contains(SECRET));
    }

    @Test
    void doctorMissingKeyFailsCleanly() {
        Captured c = new Captured();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        boolean ok = c.reporter.doctor(new LlmConfig("https://x/v1", null, "m",
                Duration.ofSeconds(5), Duration.ofSeconds(5)), tempDir, registry,
                true, null);
        assertFalse(ok);
        String text = c.text();
        assertTrue(text.contains("[FAIL] API Key"));
        assertTrue(text.contains("API key is not configured"));
    }

    @Test
    void doctorMissingWorkingDirFails() {
        Captured c = new Captured();
        boolean ok = c.reporter.doctor(fullConfig(), tempDir.resolve("nope"),
                new InMemoryToolRegistry(), true, null);
        assertFalse(ok);
        assertTrue(c.text().contains("[FAIL] Working directory"));
    }

    @Test
    void doctorConnectivityFailureShowsRedactedReason() {
        Captured c = new Captured();
        boolean ok = c.reporter.doctor(fullConfig(), tempDir, new InMemoryToolRegistry(),
                true, ConfigReporter.ConnectivityResult.httpFailure(401, "Unauthorized"));
        assertFalse(ok);
        String text = c.text();
        assertTrue(text.contains("[FAIL] LLM connectivity"));
        assertTrue(text.contains("HTTP status: 401"));
        assertFalse(text.contains(SECRET));
    }

    // ---------- LlmConnectivityProbe ----------

    @Test
    void probeSuccessReturnsNull() {
        LlmClient ok = new LlmClient() {
            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public AgentResponse chat(List<ChatMessage> messages) {
                return AgentResponse.finalAnswer("pong");
            }
        };
        assertEquals(null, LlmConnectivityProbe.probe(ok, fullConfig()));
    }

    @Test
    void probe401Classified() {
        LlmClient failing = new LlmClient() {
            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public AgentResponse chat(List<ChatMessage> messages) {
                throw new LlmException("LLM API error: HTTP 401 - Unauthorized");
            }
        };
        ConfigReporter.ConnectivityResult r = LlmConnectivityProbe.probe(failing, fullConfig());
        assertEquals("HTTP status: 401", r.status());
    }

    @Test
    void probe404Classified() {
        LlmClient failing = new LlmClient() {
            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public AgentResponse chat(List<ChatMessage> messages) {
                throw new LlmException("LLM API error: HTTP 404 - not found");
            }
        };
        ConfigReporter.ConnectivityResult r = LlmConnectivityProbe.probe(failing, fullConfig());
        assertTrue(r.reason().contains("endpoint not found"));
    }

    @Test
    void probeConnectionFailureSanitized() {
        LlmClient failing = new LlmClient() {
            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public AgentResponse chat(List<ChatMessage> messages) {
                throw new LlmException("LLM request failed (IO): connect refused " + SECRET);
            }
        };
        ConfigReporter.ConnectivityResult r = LlmConnectivityProbe.probe(failing, fullConfig());
        assertFalse(r.reason().contains(SECRET), "鎺㈤拡澶辫触鍘熷洜涓嶅緱鍖呭惈 Key");
    }

    @Test
    void sanitizeStripsApiKey() {
        String cleaned = LlmConnectivityProbe.sanitize("error " + SECRET + " tail", fullConfig());
        assertFalse(cleaned.contains(SECRET));
        assertTrue(cleaned.contains("***"));
    }

    @Test
    void doctorDoesNotCreateOrModifyFiles() throws Exception {
        // doctor 只读：临时目录中不应出现任何新文件
        Path dir = Files.createDirectories(tempDir.resolve("wd"));
        Captured c = new Captured();
        c.reporter.doctor(fullConfig(), dir, new InMemoryToolRegistry(), true, null);
        try (var stream = Files.list(dir)) {
            assertEquals(0, stream.count(), "doctor 不得创建/修改文件");
        }
    }
}
