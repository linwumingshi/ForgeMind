package com.forgemind.core.exception;

/**
 * Tool 执行超时（M2 起由 ToolExecutor 的通用超时机制抛出）。
 */
public class ToolTimeoutException extends ToolException {

    public ToolTimeoutException(String message) {
        super(message);
    }

    public ToolTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
