package com.forgemind.core.exception;

/**
 * Tool 相关异常的基类。
 */
public class ToolException extends AgentException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
