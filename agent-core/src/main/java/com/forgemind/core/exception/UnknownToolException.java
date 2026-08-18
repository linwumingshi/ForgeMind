package com.forgemind.core.exception;

/**
 * 调用了未注册的 Tool。
 *
 * <p>注：DefaultToolExecutor 会把未知 Tool 转为错误 ToolResult 回灌 LLM，
 * 本异常主要供需要显式抛出/捕获的路径使用。</p>
 */
public class UnknownToolException extends ToolException {

    public UnknownToolException(String message) {
        super(message);
    }
}
