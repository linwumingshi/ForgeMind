package com.forgemind.core.retry;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP 指数退避重试策略。
 *
 * <p>仅对可重试状态（429/500/502/503/504）重试；400/401/403/404/422 等
 * 客户端/鉴权错误立即失败。退避：initialBackoff × multiplier^(attempt-1)，
 * 上限 maxBackoff；可选 jitter（50%–100% 抖动，避免惊群；测试可关闭）。</p>
 */
public final class RetryPolicy {

    /** 可重试的 HTTP 状态码。 */
    public static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(5);
    public static final double DEFAULT_MULTIPLIER = 2.0;

    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double multiplier;
    private final boolean jitter;

    public RetryPolicy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_MULTIPLIER, true);
    }

    public RetryPolicy(int maxRetries, Duration initialBackoff, Duration maxBackoff,
                       double multiplier, boolean jitter) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must be non-negative");
        }
        if (maxBackoff == null || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("maxBackoff must be non-negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0: " + multiplier);
        }
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.multiplier = multiplier;
        this.jitter = jitter;
    }

    public boolean isRetryable(int status) {
        return RETRYABLE_STATUS.contains(status);
    }

    public int maxRetries() {
        return maxRetries;
    }

    /** 返回第 {@code attempt} 次重试前的等待时长（attempt 从 1 开始）。 */
    public Duration backoffFor(int attempt) {
        double factor = Math.pow(multiplier, attempt - 1);
        long base = (long) (initialBackoff.toMillis() * factor);
        long capped = Math.min(base, maxBackoff.toMillis());
        if (jitter && capped > 0) {
            long half = capped / 2;
            capped = half + ThreadLocalRandom.current().nextLong(half + 1);
        }
        return Duration.ofMillis(capped);
    }

    /** 返回关闭/开启 jitter 的副本。 */
    public RetryPolicy withJitter(boolean enabled) {
        return new RetryPolicy(maxRetries, initialBackoff, maxBackoff, multiplier, enabled);
    }
}
