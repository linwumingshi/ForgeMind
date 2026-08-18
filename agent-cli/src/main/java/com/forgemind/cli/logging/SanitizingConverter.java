package com.forgemind.cli.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code ClassicConverter}：pattern 中使用 {@code %sanitize} 时，
 * 对格式化后的消息统一脱敏（sk-... / Bearer ... / 动态 API Key）。
 */
public final class SanitizingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.sanitize(event.getFormattedMessage());
    }
}
