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
    }

    @Test
    void legacyConstructorsStayCompatible() {
        assertEquals(AgentConfig.defaults().contextMaxChars(), new AgentConfig(20).contextMaxChars());
        assertEquals(ToolLimits.defaults(), new AgentConfig(20).toolLimits());
        assertEquals(AgentConfig.defaults().toolOutputLimit(),
                new AgentConfig(20, ToolLimits.defaults()).toolOutputLimit());
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
