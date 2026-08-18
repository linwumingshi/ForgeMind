package com.forgemind.core.exception;

/**
 * Tool 执行过程中失败（工具内部逻辑错误、IO 失败等）。
 */
public class ToolExecutionException extends ToolException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
