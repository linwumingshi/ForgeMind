package com.forgemind.core.exception;

/**
 * 配置缺失或非法（如 maxIterations 非正数、缺少 API Key）。
 */
public class ConfigException extends AgentException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
