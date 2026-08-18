package com.forgemind.core;

import com.forgemind.core.loop.AgentLoop;
import com.forgemind.model.AgentResult;
import java.util.Objects;

/**
 * 默认 Agent 实现：把任务委托给 {@link AgentLoop}，自身不包含循环逻辑。
 */
public final class DefaultAgent implements Agent {

    private final AgentLoop loop;

    public DefaultAgent(AgentLoop loop) {
        this.loop = Objects.requireNonNull(loop, "loop");
    }

    @Override
    public AgentResult run(String task) {
        return loop.run(task);
    }
}
