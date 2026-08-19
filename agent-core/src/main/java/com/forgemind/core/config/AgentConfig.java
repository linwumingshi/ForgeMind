package com.forgemind.core.config;

import com.forgemind.core.exception.ConfigException;

/**
 * Agent 运行配置。
 *
 * <p>M7 增加 Token Budget（{@code contextMaxTokens}/{@code contextReserveTokens}）
 * 与续写上限（{@code maxContinuationAttempts}）；M9 增加
 * {@code maxSubAgents}（一次主 run 可创建的子 Agent 总数上限）。
 * M6 的字符预算 {@code contextMaxChars} 与 {@code toolOutputLimit} 保留。
 * 兼容构造全部保留，旧测试零改动。</p>
 *
 * @param maxIterations          最大循环轮数（防止 Agent 无限循环）
 * @param toolLimits             Tool 运行限额
 * @param contextMaxChars        M6 字符预算（默认 120k，0=禁用字符压缩）
 * @param toolOutputLimit        Tool 结果进 Context 前的输出上限（默认 64KB）
 * @param contextMaxTokens       Token Budget 上限（默认 100k，0=禁用 token 压缩，回退字符预算）
 * @param contextReserveTokens   为 LLM 输出预留的 token（默认 8k）
 * @param maxContinuationAttempts finish_reason=length 自动续写上限（默认 2，0=禁用续写）
 * @param maxSubAgents           一次主 run 可创建的子 Agent 总数上限（默认 5，0=禁用 SubAgent）
 */
public record AgentConfig(int maxIterations, ToolLimits toolLimits,
                          long contextMaxChars, long toolOutputLimit,
                          long contextMaxTokens, long contextReserveTokens,
                          int maxContinuationAttempts, int maxSubAgents) {

    public static final int DEFAULT_MAX_ITERATIONS = 30;
    public static final long DEFAULT_CONTEXT_MAX_CHARS = 120_000;
    public static final long DEFAULT_TOOL_OUTPUT_LIMIT = 64L * 1024;
    public static final long DEFAULT_CONTEXT_MAX_TOKENS = 100_000;
    public static final long DEFAULT_CONTEXT_RESERVE_TOKENS = 8_000;
    public static final int DEFAULT_MAX_CONTINUATION_ATTEMPTS = 2;
    public static final int DEFAULT_MAX_SUB_AGENTS = 5;

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
        if (contextMaxTokens < 0) {
            throw new ConfigException("contextMaxTokens must be >= 0: " + contextMaxTokens);
        }
        if (contextReserveTokens < 0) {
            throw new ConfigException("contextReserveTokens must be >= 0: " + contextReserveTokens);
        }
        if (contextMaxTokens > 0 && contextReserveTokens > contextMaxTokens) {
            throw new ConfigException("contextReserveTokens (" + contextReserveTokens
                    + ") must not exceed contextMaxTokens (" + contextMaxTokens + ")");
        }
        if (maxContinuationAttempts < 0) {
            throw new ConfigException("maxContinuationAttempts must be >= 0: " + maxContinuationAttempts);
        }
        if (maxSubAgents < 0) {
            throw new ConfigException("maxSubAgents must be >= 0: " + maxSubAgents);
        }
    }

    /** 兼容构造：仅指定迭代预算，其余全部默认。 */
    public AgentConfig(int maxIterations) {
        this(maxIterations, ToolLimits.defaults(), DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT,
                DEFAULT_CONTEXT_MAX_TOKENS, DEFAULT_CONTEXT_RESERVE_TOKENS, DEFAULT_MAX_CONTINUATION_ATTEMPTS,
                DEFAULT_MAX_SUB_AGENTS);
    }

    /** 兼容构造：指定迭代预算与 ToolLimits。 */
    public AgentConfig(int maxIterations, ToolLimits toolLimits) {
        this(maxIterations, toolLimits, DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT,
                DEFAULT_CONTEXT_MAX_TOKENS, DEFAULT_CONTEXT_RESERVE_TOKENS, DEFAULT_MAX_CONTINUATION_ATTEMPTS,
                DEFAULT_MAX_SUB_AGENTS);
    }

    /** M6 兼容构造：字符预算与输出限制自定义，token 配置默认。 */
    public AgentConfig(int maxIterations, ToolLimits toolLimits,
                       long contextMaxChars, long toolOutputLimit) {
        this(maxIterations, toolLimits, contextMaxChars, toolOutputLimit,
                DEFAULT_CONTEXT_MAX_TOKENS, DEFAULT_CONTEXT_RESERVE_TOKENS, DEFAULT_MAX_CONTINUATION_ATTEMPTS,
                DEFAULT_MAX_SUB_AGENTS);
    }

    /** M7 兼容构造：token/续写配置自定义，maxSubAgents 默认。 */
    public AgentConfig(int maxIterations, ToolLimits toolLimits,
                       long contextMaxChars, long toolOutputLimit,
                       long contextMaxTokens, long contextReserveTokens,
                       int maxContinuationAttempts) {
        this(maxIterations, toolLimits, contextMaxChars, toolOutputLimit,
                contextMaxTokens, contextReserveTokens, maxContinuationAttempts,
                DEFAULT_MAX_SUB_AGENTS);
    }

    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_MAX_ITERATIONS, ToolLimits.defaults(),
                DEFAULT_CONTEXT_MAX_CHARS, DEFAULT_TOOL_OUTPUT_LIMIT,
                DEFAULT_CONTEXT_MAX_TOKENS, DEFAULT_CONTEXT_RESERVE_TOKENS,
                DEFAULT_MAX_CONTINUATION_ATTEMPTS, DEFAULT_MAX_SUB_AGENTS);
    }

    /** 实际可用的 Context Budget：max(0, max - reserve)。 */
    public long usableContextTokens() {
        return Math.max(0, contextMaxTokens - contextReserveTokens);
    }
}
