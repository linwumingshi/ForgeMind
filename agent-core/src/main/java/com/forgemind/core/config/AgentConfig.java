package com.forgemind.core.config;

import com.forgemind.core.exception.ConfigException;

/**
 * Agent 运行配置。
 *
 * <p>M4 起 {@link ToolLimits} 并入本配置；M6 增加 {@code contextMaxChars}（上下文
 * 字符预算，0 表示禁用压缩）与 {@code toolOutputLimit}（进入 LLM Context 前的
 * Tool 输出上限）。兼容构造保留，旧测试零改动。</p>
 *
 * @param maxIterations   最大循环轮数（防止 Agent 无限循环）
 * @param toolLimits      Tool 运行限额（默认值见 {@link ToolLimits#defaults()})
 * @param contextMaxChars 上下文粗略字符预算（默认 120k，0=禁用 ContextCompactor）
 * @param toolOutputLimit Tool 结果进 Context 前的输出上限（默认 64KB）
 */
public record AgentConfig(int maxIterations, ToolLimits toolLimits,
                          long contextMaxChars, long toolOutputLimit) {

    public static final int DEFAULT_MAX_ITERATIONS = 30;
    public static final long DEFAULT_CONTEXT_MAX_CHARS = 120_000;
    public static final long DEFAULT_TOOL_OUTPUT_LIMIT = 64L * 1024;

    public AgentConfig {
        if (maxIterations <= 0) {
            throw new ConfigException("maxIterations must be positive: " + maxIterations);
        }
        toolLimits = toolLimits == null ? ToolLimits.defaults() : toolLimits;
        if (contextMaxChars < 0) {
            throw new ConfigException("contextMaxChars must be >= 0: " + contextMaxChars);
        }
        if (toolOutputLimit <= 0) {
            throw new ConfigException("toolOutputLimit must be positive: " + toolOutputLimit);
        }
    }

    /** 兼容构造：仅指定迭代预算，其余全部默认。 */
    public AgentConfig(int maxIterations) {
        this(maxIterations, ToolLimits.defaults(), DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT);
    }

    /** 兼容构造：指定迭代预算与 ToolLimits，上下文配置默认。 */
    public AgentConfig(int maxIterations, ToolLimits toolLimits) {
        this(maxIterations, toolLimits, DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT);
    }

    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_MAX_ITERATIONS, ToolLimits.defaults(),
                DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT);
    }
}
