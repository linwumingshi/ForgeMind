package com.forgemind.core.exception;

/**
 * Agent 循环迭代次数超过预算（防止无限循环）。
 */
public class MaxIterationsExceededException extends AgentException {

    public MaxIterationsExceededException(String message) {
        super(message);
    }
}
