package com.forgemind.core.exception;

/**
 * Tool 参数校验失败：缺少 required 参数或参数类型不匹配。
 */
public class InvalidToolArgumentsException extends ToolException {

    public InvalidToolArgumentsException(String message) {
        super(message);
    }
}
