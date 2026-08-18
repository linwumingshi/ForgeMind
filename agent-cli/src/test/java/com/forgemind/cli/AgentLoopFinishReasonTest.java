package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * finish_reason 在 AgentLoop 中的行为：length 不视为异常；空 content + length
 * 走既有畸形响应机制。
 */
class AgentLoopFinishReasonTest {

    @TempDir
    Path workspace;

    private Agent agent(FakeLlmClient fake) {
        return CliAssembly.buildAgent(AgentConfig.defaults(), fake, workspace, req -> false);
    }

    @Test
    void lengthWithContentCompletesNormally() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("partial but acceptable", null, "length"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished(), "finish_reason=length 不应导致失败");
        assertEquals("partial but acceptable", result.finalAnswer());
    }

    @Test
    void lengthWithEmptyContentIsInvalidThenRecovers() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason(null, null, "length"))
                .then(AgentResponse.finalAnswer("recovered"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished(), "空 content + length 一次应允许自纠");
        assertEquals("recovered", result.finalAnswer());
    }

    @Test
    void consecutiveLengthEmptyContentFailsCleanly() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason(null, null, "length"))
                .then(AgentResponse.withFinishReason(null, null, "length"))
                .then(AgentResponse.withFinishReason(null, null, "length"));
        AgentResult result = agent(fake).run("task");
        assertFalse(result.finished());
        assertTrue(result.error().contains("invalid responses"));
    }

    @Test
    void unknownFinishReasonDoesNotCrash() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withFinishReason("ok anyway", null, "weird_value"));
        AgentResult result = agent(fake).run("task");
        assertTrue(result.finished());
        assertEquals("ok anyway", result.finalAnswer());
    }
}
