package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ForgemindCommandTest {

    @TempDir
    Path tempDir;

    /** 注入 Fake LLM 与固定配置加载器；输出进入匿名流（通过副作用/调用记录断言）。 */
    private ForgemindCommand commandWith(FakeLlmClient fake) {
        java.nio.file.Path userHome = tempDir.resolve("home-empty");
        return new ForgemindCommand(cfg -> fake, file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                AgentConfig.defaults(),
                new LlmConfig("http://x/v1", "k", "m", Duration.ofSeconds(5), Duration.ofSeconds(5))),
                s -> new com.forgemind.cli.config.UserConfigStore(
                        userHome.resolve(".forgemind/config.yml")),
                new PrintStream(new ByteArrayOutputStream()), new ByteArrayInputStream(new byte[0]));
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.of(id, name, args);
    }

    @Test
    void helpOptionPrintsUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cmdLine = new CommandLine(new ForgemindCommand(cfg -> new FakeLlmClient(),
                file -> null, new PrintStream(out), new ByteArrayInputStream(new byte[0])));
        cmdLine.setOut(new PrintWriter(out, true));
        int exit = cmdLine.execute("--help");
        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void runsSingleTaskModeWithWorkingDir() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "hello");
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "read_file", Map.of("path", "a.txt")))))
                .then(AgentResponse.finalAnswer("task done"));
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "--yes", "read the file");
        assertEquals(0, exit);
        // 两轮调用：工具调用 + 最终答案
        assertEquals(2, fake.callCount());
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("hello"), "工具应在指定工作目录执行");
    }

    @Test
    void yesFlagAllowsWritePermission() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "write_file", Map.of("path", "created.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("written"));
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "--yes", "create a file");
        assertEquals(0, exit);
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("created.txt")),
                "--yes 应自动允许 WRITE 权限询问");
    }

    @Test
    void withoutYesWriteIsDeniedByDefault() throws Exception {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(call("c1", "write_file", Map.of("path", "denied.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("done"));
        // 无 --yes：InteractivePermissionAnswerer 读到 EOF → 视为 N（拒绝）
        int exit = new CommandLine(commandWith(fake))
                .execute("--working-dir", tempDir.toString(), "create a file");
        assertEquals(0, exit);
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("denied.txt")),
                "默认权限策略应拒绝写入");
        ChatMessage toolMsg = fake.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"));
    }

    @Test
    void defaultWorkingDirIsCurrentDirectory() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        int exit = new CommandLine(commandWith(fake)).execute("--yes", "task");
        assertEquals(0, exit);
        assertEquals(1, fake.callCount());
    }

    // ---------- M9.5.2.1：LLM 配置参数 ----------

    /** --provider/--base-url/--model/--api-key 必须传递到 llmFactory 收到的 cfg。 */
    @Test
    void llmConfigOptionsReachLlmFactory() {
        java.nio.file.Path userHome = tempDir.resolve("home-cli-overrides");
        CapturingFactory factory = new CapturingFactory();
        ForgemindCommand cmd = new ForgemindCommand(cfg -> {
            factory.cfg = cfg;
            return new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        }, file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                AgentConfig.defaults(), null),
                s -> new com.forgemind.cli.config.UserConfigStore(
                        userHome.resolve(".forgemind/config.yml")),
                new PrintStream(new ByteArrayOutputStream()), new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute(
                "--provider", "deepseek", "--model", "deepseek-chat",
                "--base-url", "https://proxy.example/v1", "--api-key", "cli-key", "--yes", "t");
        assertEquals(0, exit);
        assertEquals("https://proxy.example/v1", factory.cfg.baseUrl());
        assertEquals("deepseek-chat", factory.cfg.model());
        assertEquals("cli-key", factory.cfg.apiKey());
    }

    /** 无 --base-url 时 provider 默认 baseUrl 应生效（用户无需改源码）。 */
    @Test
    void providerDefaultBaseUrlUsedWithoutExplicitBaseUrl() {
        java.nio.file.Path userHome = tempDir.resolve("home-provider-default");
        CapturingFactory factory = new CapturingFactory();
        ForgemindCommand cmd = new ForgemindCommand(cfg -> {
            factory.cfg = cfg;
            return new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        }, file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                AgentConfig.defaults(), null),
                s -> new com.forgemind.cli.config.UserConfigStore(
                        userHome.resolve(".forgemind/config.yml")),
                new PrintStream(new ByteArrayOutputStream()), new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute(
                "--provider", "deepseek", "--model", "deepseek-chat", "--yes", "t");
        assertEquals(0, exit);
        assertEquals("https://api.deepseek.com", factory.cfg.baseUrl());
        assertEquals("deepseek-chat", factory.cfg.model());
    }

    private static final class CapturingFactory {
        com.forgemind.core.config.LlmConfig cfg;
    }

    // ---------- M9.5.2.2：--configure 与用户级配置 ----------

    /** --configure 应进入向导并保存到用户级配置（测试注入临时目录）。 */
    @Test
    void configureSavesUserConfig() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home");
        ForgemindCommand cmd = commandWithConfiguredStore(
                new com.forgemind.cli.config.UserConfigStore(
                        userHome.resolve(".forgemind/config.yml")),
                "deepseek\ntest-key\n\n\n"); // provider, key, baseUrl(默认), model(默认)
        int exit = new CommandLine(cmd).execute("--configure");
        assertEquals(0, exit);
        java.nio.file.Path saved = userHome.resolve(".forgemind/config.yml");
        assertTrue(java.nio.file.Files.exists(saved), "配置应保存到用户级目录");
        String content = java.nio.file.Files.readString(saved, StandardCharsets.UTF_8);
        assertTrue(content.contains("provider: deepseek"));
        assertTrue(content.contains("test-key"));
        assertFalse(content.contains("Provider: deepseek"), "保存内容是 YAML 不是向导回显");
    }

    /** --configure 后再次运行：直接从用户配置加载，不再询问（不进入向导）。 */
    @Test
    void configuredRunSkipsWizardAndUsesSavedConfig() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home2");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml"));
        store.save(new com.forgemind.cli.config.UserConfigStore.UserConfig(
                "deepseek", "test-key", "https://api.deepseek.com", "deepseek-chat",
                null, null));
        CapturingFactory factory = new CapturingFactory();
        ForgemindCommand cmd = commandWithConfiguredStore(store, "", cfg -> {
            factory.cfg = cfg;
            return new FakeLlmClient().then(AgentResponse.finalAnswer("ok"));
        });
        int exit = new CommandLine(cmd).execute("--yes", "task");
        assertEquals(0, exit);
        assertEquals("https://api.deepseek.com", factory.cfg.baseUrl());
        assertEquals("deepseek-chat", factory.cfg.model());
        assertEquals("test-key", factory.cfg.apiKey());
    }

    /** 首次启动（无配置、无 CLI/ENV）→ 进入向导并保存，然后可继续。 */
    @Test
    void firstRunWithoutConfigEntersWizard() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home3");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml"));
        ForgemindCommand cmd = new ForgemindCommand(
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")),
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                () -> true, // 强制 interactive → 触发首启向导
                new PrintStream(new ByteArrayOutputStream()),
                new ByteArrayInputStream("deepseek\ntest-key\n\n\n".getBytes(StandardCharsets.UTF_8)));
        int exit = new CommandLine(cmd).execute("--yes", "task");
        assertEquals(0, exit);
        assertTrue(java.nio.file.Files.exists(userHome.resolve(".forgemind/config.yml")),
                "首次启动应保存配置");
    }

    private ForgemindCommand commandWithConfiguredStore(
            com.forgemind.cli.config.UserConfigStore store, String input) {
        return commandWithConfiguredStore(store, input,
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")));
    }

    private ForgemindCommand commandWithConfiguredStore(
            com.forgemind.cli.config.UserConfigStore store, String input,
            Function<LlmConfig, LlmClient> factory) {
        return new ForgemindCommand(factory,
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                new PrintStream(new ByteArrayOutputStream()),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------- M9.5.2.3：--config-show / --doctor ----------

    /** --config-show 显示最终配置且 Key 脱敏。 */
    @Test
    void configShowDisplaysEffectiveConfigWithoutKey() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home-show");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml"));
        store.save(new com.forgemind.cli.config.UserConfigStore.UserConfig(
                "deepseek", "test-key", "https://api.deepseek.com", "deepseek-chat",
                null, null));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ForgemindCommand cmd = new ForgemindCommand(
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")),
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute("--config-show");
        assertEquals(0, exit);
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Provider: deepseek"));
        assertTrue(text.contains("Base URL: https://api.deepseek.com"));
        assertTrue(text.contains("Model: deepseek-chat"));
        assertTrue(text.contains("API Key: configured"));
        assertFalse(text.contains("test-key"), "config-show 不得输出真实 Key");
    }

    /** --config-show 环境变量 Key 不泄漏。 */
    @Test
    void configShowDoesNotLeakEnvApiKey() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home-env");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml"));
        store.save(new com.forgemind.cli.config.UserConfigStore.UserConfig(
                "deepseek", null, "https://api.deepseek.com", "deepseek-chat", null, null));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // 用 CLI --api-key 模拟 env 来源（test-secret-key）
        ForgemindCommand cmd = new ForgemindCommand(
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")),
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute("--config-show", "--api-key", "test-secret-key");
        assertEquals(0, exit);
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("API Key: configured"));
        assertFalse(text.contains("test-secret-key"));
    }

    /** --doctor 全部通过 → exit 0；不输出 Key。 */
    @Test
    void doctorAllPassesExitZero() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home-doc");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml"));
        store.save(new com.forgemind.cli.config.UserConfigStore.UserConfig(
                "deepseek", "test-key", "https://api.deepseek.com", "deepseek-chat", null, null));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ForgemindCommand cmd = new ForgemindCommand(
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")),
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute("--doctor", "--working-dir", tempDir.toString());
        assertEquals(0, exit, "全部检查通过时 --doctor 应 exit 0");
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[OK] Configuration"));
        assertTrue(text.contains("[OK] LLM connectivity"));
        assertFalse(text.contains("test-key"), "doctor 不得输出 Key");
        assertFalse(text.contains("Authorization"));
    }

    /** --doctor API Key 缺失 → exit 非 0，输出清晰诊断，不抛 stacktrace。 */
    @Test
    void doctorMissingKeyFailsWithClearDiagnostic() throws Exception {
        java.nio.file.Path userHome = tempDir.resolve("home-doc2");
        com.forgemind.cli.config.UserConfigStore store = new com.forgemind.cli.config.UserConfigStore(
                userHome.resolve(".forgemind/config.yml")); // 空配置
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ForgemindCommand cmd = new ForgemindCommand(
                cfg -> new FakeLlmClient().then(AgentResponse.finalAnswer("ok")),
                file -> new com.forgemind.cli.config.ConfigLoader.Loaded(
                        AgentConfig.defaults(), null),
                s -> store,
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        int exit = new CommandLine(cmd).execute("--doctor", "--working-dir", tempDir.toString());
        assertEquals(1, exit, "存在诊断失败时 --doctor 应 exit 非 0");
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[FAIL] API Key"));
        assertTrue(text.contains("API key is not configured"));
        assertFalse(text.contains("Exception"), "不应出现 Java stacktrace");
        assertFalse(text.contains("at com.forgemind"));
    }
}
