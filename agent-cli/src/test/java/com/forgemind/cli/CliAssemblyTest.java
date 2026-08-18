package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.exception.ConfigException;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliAssemblyTest {

    @TempDir
    Path tempDir;

    @Test
    void standardToolsContainNineTools() {
        assertEquals(9, CliAssembly.standardTools().size());
        var names = CliAssembly.standardTools().stream().map(com.forgemind.core.tool.AgentTool::name).toList();
        assertTrue(names.contains("list_files"));
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("write_file"));
        assertTrue(names.contains("edit_file"));
        assertTrue(names.contains("search"));
        assertTrue(names.contains("shell"));
        assertTrue(names.contains("git_status"));
        assertTrue(names.contains("git_diff"));
        assertTrue(names.contains("git_commit"));
    }

    @Test
    void buildAgentRunsEndToEndWithFakeLlm() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "hello");
        FakeLlmClient fake = new FakeLlmClient()
                .then(com.forgemind.model.AgentResponse.withToolCalls(null,
                        java.util.List.of(com.forgemind.model.ToolCall.of("c1", "read_file",
                                java.util.Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("done"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir, req -> false);
        AgentResult result = agent.run("read a.txt");
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
        assertEquals(1, result.toolCallCount());
    }

    @Test
    void toolLimitsFromConfigReachExecutor() throws Exception {
        // 配置 readFileMaxBytes=10：executor 必须使用该限额，超大文件读取应失败
        java.nio.file.Files.write(tempDir.resolve("big.txt"), new byte[100]);
        AgentConfig config = new AgentConfig(30, ToolLimits.defaults().withReadFileMaxBytes(10));
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        java.util.List.of(com.forgemind.model.ToolCall.of("c1", "read_file",
                                java.util.Map.of("path", "big.txt")))))
                .then(AgentResponse.finalAnswer("recovered"));
        Agent agent = CliAssembly.buildAgent(config, fake, tempDir, req -> false);
        AgentResult result = agent.run("t");
        assertTrue(result.finished());
        assertTrue(fake.calls().get(1).stream().anyMatch(m -> m.content() != null
                && m.content().contains("file too large")));
    }

    @Test
    void validateLlmRejectsMissingApiKeyWithoutLeaking() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> CliAssembly.validateLlm(LlmConfig.defaults().withModel("m")));
        assertTrue(e.getMessage().contains("FORGEMIND_API_KEY"));
        assertFalseContainsSecret(e);
    }

    @Test
    void validateLlmRejectsMissingModel() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> CliAssembly.validateLlm(LlmConfig.defaults().withApiKey("some-key")));
        assertTrue(e.getMessage().contains("model"));
    }

    @Test
    void validateLlmAcceptsCompleteConfig() {
        CliAssembly.validateLlm(new LlmConfig("https://x/v1", "k", "m",
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)));
    }

    private static void assertFalseContainsSecret(ConfigException e) {
        // 错误信息中不得出现任何疑似 Key 的内容
        assertTrue(!e.getMessage().contains("some-key")
                && !e.getMessage().toLowerCase().contains("bearer")
                && !e.getMessage().contains("sk-"));
    }
}
