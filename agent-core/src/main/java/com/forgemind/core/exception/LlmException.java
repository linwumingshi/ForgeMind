package com.forgemind.core.exception;

/**
 * LLM 调用不可恢复错误：网络失败、鉴权失败、5xx 且重试耗尽等。
 */
public class LlmException extends AgentException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
