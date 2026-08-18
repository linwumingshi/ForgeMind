package com.forgemind.core.exception;

/**
 * 路径越界：尝试访问工作目录之外的路径（".." 逃逸、绝对路径越界、符号链接逃逸）。
 * 属于安全事件，必须记录日志。
 */
public class PathEscapeException extends ToolException {

    public PathEscapeException(String message) {
        super(message);
    }

    public PathEscapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
