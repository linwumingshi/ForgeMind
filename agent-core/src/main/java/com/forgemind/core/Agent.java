package com.forgemind.core;

import com.forgemind.model.AgentResult;

/**
 * Agent 对外门面：接收自然语言任务，返回最终结果。
 * CLI / Web / 测试共用同一入口。
 */
public interface Agent {

    /**
     * 同步执行一个任务。
     *
     * @param task 自然语言任务描述
     * @return 最终结果（含最终答案或终止原因）
     */
    AgentResult run(String task);
}
