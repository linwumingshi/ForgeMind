package com.forgemind.core.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.AgentResult;
import org.junit.jupiter.api.Test;

/**
 * M9.1：SubAgentFactory 契约（最小实现验证接口形状）。
 */
class SubAgentFactoryTest {

    /** 测试用最小实现：直接按 spec 生成结果。 */
    private static final class StubFactory implements SubAgentFactory {
        private final int max;

        StubFactory(int max) {
            this.max = max;
        }

        @Override
        public AgentResult run(SubAgentSpec spec) {
            return AgentResult.completed("done:" + spec.task(), 1, 0);
        }

        @Override
        public int maxSubAgents() {
            return max;
        }
    }

    @Test
    void runReturnsAgentResult() {
        SubAgentFactory factory = new StubFactory(5);
        AgentResult result = factory.run(SubAgentSpec.of("subtask"));
        assertTrue(result.finished());
        assertEquals("done:subtask", result.finalAnswer());
    }

    @Test
    void maxSubAgentsIsExposed() {
        assertEquals(5, new StubFactory(5).maxSubAgents());
        assertEquals(0, new StubFactory(0).maxSubAgents());
    }

    @Test
    void failedResultCanBeReturned() {
        SubAgentFactory factory = new SubAgentFactory() {
            @Override
            public AgentResult run(SubAgentSpec spec) {
                return AgentResult.failed("partial", 3, 2, "boom");
            }

            @Override
            public int maxSubAgents() {
                return 1;
            }
        };
        AgentResult result = factory.run(SubAgentSpec.of("x"));
        assertFalse(result.finished());
        assertEquals("boom", result.error());
    }
}
