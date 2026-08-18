package com.forgemind.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forgemind.core.exception.ConfigException;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void defaultsAreSane() {
        AgentConfig config = AgentConfig.defaults();
        assertEquals(30, config.maxIterations());
        assertEquals(ToolLimits.defaults(), config.toolLimits());
        assertEquals(120_000, config.contextMaxChars());
        assertEquals(64L * 1024, config.toolOutputLimit());
        assertEquals(100_000, config.contextMaxTokens());
        assertEquals(8_000, config.contextReserveTokens());
        assertEquals(2, config.maxContinuationAttempts());
        assertEquals(92_000, config.usableContextTokens());
    }

    @Test
    void tokenBudgetValidation() {
        // reserve > max 且 max > 0 → 明确报错
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 5000, 8000, 2));
        // max=0（禁用 token）时 reserve 任意合法
        assertEquals(0, new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 0, 8000, 2)
                .usableContextTokens());
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, -1, 0, 2));
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 1000, -1, 2));
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 1000, 0, -1));
    }

    @Test
    void usableContextTokensIsMaxMinusReserve() {
        AgentConfig config = new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 100, 50, 1);
        assertEquals(50, config.usableContextTokens());
        // max=0（禁用）时 reserve 不参与，usable 为 0
        assertEquals(0, new AgentConfig(10, ToolLimits.defaults(), 1000, 1024, 0, 8000, 1)
                .usableContextTokens());
    }

    @Test
    void legacyConstructorsStayCompatible() {
        assertEquals(AgentConfig.defaults().contextMaxChars(), new AgentConfig(20).contextMaxChars());
        assertEquals(ToolLimits.defaults(), new AgentConfig(20).toolLimits());
        assertEquals(AgentConfig.defaults().toolOutputLimit(),
                new AgentConfig(20, ToolLimits.defaults()).toolOutputLimit());
        assertEquals(AgentConfig.defaults().contextMaxTokens(),
                new AgentConfig(20, ToolLimits.defaults(), 50_000, 4096).contextMaxTokens());
    }

    @Test
    void contextMaxCharsAllowsZeroDisable() {
        assertEquals(0, new AgentConfig(10, ToolLimits.defaults(), 0, 1024).contextMaxChars());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), -1, 1024));
        assertThrows(ConfigException.class,
                () -> new AgentConfig(10, ToolLimits.defaults(), 1000, 0));
    }
}
