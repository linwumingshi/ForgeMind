package com.forgemind.core.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void retryableStatuses() {
        RetryPolicy policy = new RetryPolicy();
        for (int status : new int[]{429, 500, 502, 503, 504}) {
            assertTrue(policy.isRetryable(status), "应重试 " + status);
        }
        for (int status : new int[]{400, 401, 403, 404, 422}) {
            assertFalse(policy.isRetryable(status), "不应重试 " + status);
        }
    }

    @Test
    void exponentialBackoffWithoutJitter() {
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(5), 2.0, false);
        assertEquals(Duration.ofMillis(500), policy.backoffFor(1));
        assertEquals(Duration.ofMillis(1000), policy.backoffFor(2));
        assertEquals(Duration.ofMillis(2000), policy.backoffFor(3));
    }

    @Test
    void backoffIsCappedAtMax() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(500), Duration.ofSeconds(5), 2.0, false);
        assertEquals(Duration.ofSeconds(5), policy.backoffFor(5));
        assertEquals(Duration.ofSeconds(5), policy.backoffFor(10));
    }

    @Test
    void jitterStaysWithinBackoffRange() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1000), Duration.ofSeconds(5), 2.0, true);
        for (int i = 0; i < 100; i++) {
            long millis = policy.backoffFor(1).toMillis();
            assertTrue(millis >= 500 && millis <= 1000, "jitter 应在 50%-100% 之间: " + millis);
        }
    }

    @Test
    void withJitterProducesNewInstance() {
        RetryPolicy jittery = new RetryPolicy();
        RetryPolicy deterministic = jittery.withJitter(false);
        assertTrue(deterministic.backoffFor(1).equals(Duration.ofMillis(500)));
    }

    @Test
    void defaults() {
        RetryPolicy policy = new RetryPolicy();
        assertEquals(2, policy.maxRetries());
        assertTrue(policy.isRetryable(429));
    }
}
