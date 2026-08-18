package com.forgemind.core.exception;

/**
 * Agent 运行异常的基类。所有核心异常都继承自本类，保证上层可以统一捕获。
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
