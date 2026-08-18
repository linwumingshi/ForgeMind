package com.forgemind.core.exception;

/**
 * LLM 返回非法 Tool Call（如连续多次畸形响应），Agent 无法继续自行纠正。
 */
public class InvalidToolCallException extends AgentException {

    public InvalidToolCallException(String message) {
        super(message);
    }

    public InvalidToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
