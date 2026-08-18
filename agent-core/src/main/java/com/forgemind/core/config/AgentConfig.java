package com.forgemind.core.config;

import com.forgemind.core.exception.ConfigException;

/**
 * Agent 运行配置。
 *
 * <p>M4 起 {@link ToolLimits} 并入本配置（架构 §8.2 最终形态），由装配层
 * 把 {@link #toolLimits()} 传给 ToolExecutor，保持单一配置来源。</p>
 *
 * @param maxIterations 最大循环轮数（防止 Agent 无限循环）
 * @param toolLimits    Tool 运行限额（默认值见 {@link ToolLimits#defaults()})
 */
public record AgentConfig(int maxIterations, ToolLimits toolLimits) {

    public static final int DEFAULT_MAX_ITERATIONS = 30;

    public AgentConfig {
        if (maxIterations <= 0) {
            throw new ConfigException("maxIterations must be positive: " + maxIterations);
        }
        toolLimits = toolLimits == null ? ToolLimits.defaults() : toolLimits;
    }

    /** 兼容构造：仅指定迭代预算，ToolLimits 使用默认值。 */
    public AgentConfig(int maxIterations) {
        this(maxIterations, ToolLimits.defaults());
    }

    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_MAX_ITERATIONS, ToolLimits.defaults());
    }
}
