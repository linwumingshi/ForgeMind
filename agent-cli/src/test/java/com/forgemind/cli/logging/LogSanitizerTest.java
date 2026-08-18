package com.forgemind.cli.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void masksSkKeys() {
        String out = LogSanitizer.sanitize("using key sk-abc123XYZ456 now");
        assertFalse(out.contains("sk-abc123XYZ456"));
        assertTrue(out.contains("sk-***"));
    }

    @Test
    void masksBearerTokens() {
        String out = LogSanitizer.sanitize("Authorization: Bearer abcDEF123 header");
        assertFalse(out.contains("abcDEF123"));
        assertTrue(out.contains("Bearer ***"));
    }

    @Test
    void masksDynamicApiKeyNotInSkFormat() {
        String out = LogSanitizer.sanitize(
                "configured apiKey=my-custom-token-9999 ready", "my-custom-token-9999");
        assertFalse(out.contains("my-custom-token-9999"));
        assertTrue(out.contains("***"));
    }

    @Test
    void leavesPlainTextUntouched() {
        String plain = "compiling 42 files, running tests, all green";
        assertEquals(plain, LogSanitizer.sanitize(plain, "unrelated-key"));
    }

    @Test
    void sanitizesExceptionMessages() {
        String message = "LLM API error: HTTP 401 - invalid key sk-zzz123456789 received";
        String out = LogSanitizer.sanitize(message);
        assertFalse(out.contains("sk-zzz123456789"));
        assertTrue(out.contains("sk-***"));
    }

    @Test
    void masksKeyInsideLlmExceptionStyleText() {
        String message = "failed: Bearer secret-token-value, retry";
        assertEquals("failed: Bearer ***, retry", LogSanitizer.sanitize(message, "secret-token-value"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals(null, LogSanitizer.sanitize(null));
        assertEquals("", LogSanitizer.sanitize(""));
    }
}
