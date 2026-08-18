package com.forgemind.core.retry;

import java.time.Duration;

/**
 * 重试等待抽象：生产用真实 {@link Thread#sleep}；测试可注入
 * {@link #NOOP} 避免真实等待。
 */
public interface Sleeper {

    /** 生产实现：Thread.sleep。 */
    Sleeper REAL = duration -> {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    /** 测试实现：立即返回，不实际等待。 */
    Sleeper NOOP = duration -> {
    };

    void sleep(Duration duration);
}
