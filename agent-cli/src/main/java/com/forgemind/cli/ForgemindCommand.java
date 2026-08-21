package com.forgemind.cli;

import com.forgemind.cli.config.ConfigLoader;
import com.forgemind.cli.config.ConfigReporter;
import com.forgemind.cli.config.ConfigWizard;
import com.forgemind.cli.config.LlmConfigResolver;
import com.forgemind.cli.config.LlmConnectivityProbe;
import com.forgemind.cli.config.UserConfigStore;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.llm.openai.OpenAiCompatibleLlmClient;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * ForgeMind CLI 命令（picocli）。
 *
 * <p>默认使用真实 OpenAI-Compatible 客户端；测试可通过构造函数注入 Fake。
 * 装配（buildAgent）与运行（ForgemindApp）均为独立可测类，本类只做参数解析与编排。</p>
 */
@Command(name = "forgemind", mixinStandardHelpOptions = true,
        version = "ForgeMind 0.1.0",
        description = "ForgeMind: a coding agent similar to Claude Code / Codex")
public final class ForgemindCommand implements Callable<Integer> {

    @Option(names = "--working-dir", description = "Working directory (default: current directory)")
    private Path workingDir;

    @Option(names = "--yes", description = "Auto-approve all permission requests")
    private boolean yes;

    /** P2.4：verbose 模式 —— 展示 assistant 中间文本与完整 tool output（仅展示层，不改变 Agent 行为）。 */
    @Option(names = "--verbose", description = "Show assistant intermediate text and full tool output")
    private boolean verbose;

    @Option(names = "--config", description = "Path to YAML config file")
    private Path config;

    @Option(names = "--provider", description = "LLM provider: openai | deepseek | custom")
    private String provider;

    @Option(names = "--api-key", description = "LLM API key (prefer FORGEMIND_API_KEY env var)")
    private String apiKey;

    @Option(names = "--base-url", description = "LLM base URL (overrides provider default)")
    private String baseUrl;

    @Option(names = "--model", description = "LLM model name (overrides provider default)")
    private String model;

    @Option(names = "--configure", description = "Re-run the interactive LLM configuration wizard")
    private boolean configure;

    @Option(names = "--config-show", description = "Show effective LLM configuration (redacted)")
    private boolean configShow;

    @Option(names = "--doctor", description = "Run read-only environment diagnostics")
    private boolean doctor;

    @Parameters(index = "0", arity = "0..1",
            description = "Task to run once (omit to enter interactive REPL)")
    private String task;

    private final Function<LlmConfig, LlmClient> llmFactory;
    private final Function<Path, ConfigLoader.Loaded> configLoader;
    private final Function<UserConfigStore, UserConfigStore> userConfigStoreFactory;
    private final java.util.function.BooleanSupplier interactive;
    private final PrintStream out;
    private final InputStream in;

    public ForgemindCommand() {
        this(defaultLlmFactory(), ConfigLoader::load, Function.identity(),
                () -> System.console() != null, System.out, System.in);
    }

    ForgemindCommand(Function<LlmConfig, LlmClient> llmFactory,
                     Function<Path, ConfigLoader.Loaded> configLoader,
                     PrintStream out, InputStream in) {
        this(llmFactory, configLoader, Function.identity(),
                () -> System.console() != null && in == System.in, out, in);
    }

    /** 测试可注入 userConfigStoreFactory（如指向临时目录的 store）。 */
    ForgemindCommand(Function<LlmConfig, LlmClient> llmFactory,
                     Function<Path, ConfigLoader.Loaded> configLoader,
                     Function<UserConfigStore, UserConfigStore> userConfigStoreFactory,
                     PrintStream out, InputStream in) {
        this(llmFactory, configLoader, userConfigStoreFactory,
                () -> System.console() != null && in == System.in, out, in);
    }

    /** 全注入构造（测试可强制 interactive=true 验证首启向导）。 */
    ForgemindCommand(Function<LlmConfig, LlmClient> llmFactory,
                     Function<Path, ConfigLoader.Loaded> configLoader,
                     Function<UserConfigStore, UserConfigStore> userConfigStoreFactory,
                     java.util.function.BooleanSupplier interactive,
                     PrintStream out, InputStream in) {
        this.llmFactory = llmFactory;
        this.configLoader = configLoader;
        this.userConfigStoreFactory = userConfigStoreFactory;
        this.interactive = interactive;
        this.out = out;
        this.in = in;
    }

    private static Function<LlmConfig, LlmClient> defaultLlmFactory() {
        return cfg -> {
            CliAssembly.validateLlm(cfg);
            return new OpenAiCompatibleLlmClient(cfg, CliAssembly.standardTools());
        };
    }

    @Override
    public Integer call() {
        // M9.5.2.2：用户级配置（~/.forgemind/config.yml）作为默认来源。
        UserConfigStore userConfigStore = userConfigStoreFactory.apply(new UserConfigStore());
        if (configure) {
            runConfigure(userConfigStore);
            return 0;
        }
        if (configShow) {
            runConfigShow(userConfigStore);
            return 0;
        }
        if (doctor) {
            return runDoctor(userConfigStore);
        }

        ConfigLoader.Loaded loaded = config != null
                ? configLoader.apply(config)
                : new ConfigLoader.Loaded(AgentConfig.defaults(), null);
        Path wd = resolveWorkingDir(workingDir);

        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
        PermissionAnswerer answerer = yes
                ? request -> true
                : new InteractivePermissionAnswerer(out, scanner);
        // M9.5：字段级优先级合并 —— CLI > 环境变量 > 用户级配置 > 显式 --config > Provider 默认。
        Map<String, String> env = System.getenv();
        UserConfigStore.UserConfig userConfig = userConfigStore.load();
        LlmConfig llm = resolveLlm(loaded, userConfig);
        // 首次启动：无有效配置，且没有任何显式来源（CLI/ENV/用户配置/显式 --config），
        // 且运行在真实交互终端（interactive=true）→ 进入配置向导。
        // 自动化环境（console=null，如测试/管道）不触发向导，走 validateLlm 明确报错，
        // 避免在无输入流时阻塞。
        if (!hasEffectiveConfig(llm, env) && !userConfigStore.exists()
                && provider == null && apiKey == null && baseUrl == null && model == null
                && env.get("FORGEMIND_PROVIDER") == null
                && config == null
                && interactive.getAsBoolean()) {
            out.println("ForgeMind first-time setup");
            out.println("No LLM configuration found.");
            out.println();
            UserConfigStore.UserConfig saved = runConfigure(userConfigStore);
            userConfig = saved;
            llm = resolveLlm(loaded, userConfig);
        }
        LlmClient llmClient = llmFactory.apply(llm);
        // M8.5/M9.4：CLI 观察层 —— 文本增量实时输出 + Tool/SubAgent 调用与结果展示；
        // 同一实例注入 AgentLoop（事件源）与 ForgemindApp（避免重复 final answer、状态摘要）。
        // chat()（非流式）同样兼容（无文本增量，Tool 展示与最终答案不变）。
        // P2.4：--verbose 仅控制展示层（assistant 中间文本 + 完整 tool output），
        // 不改变 logger、不改变 AgentLoop、不改变任何 Agent 行为。
        StreamingProgressRenderer renderer = new StreamingProgressRenderer(out);
        renderer.setVerbose(verbose);
        com.forgemind.core.Agent agent = CliAssembly.buildAgent(
                loaded.agent(), llmClient, wd, answerer, renderer);
        new ForgemindApp(out, scanner, renderer).run(agent, task, wd);
        return 0;
    }

    /** 合并 CLI > ENV > 用户配置 > 显式 --config > Provider 默认（复用 resolver）。 */
    private LlmConfig resolveLlm(ConfigLoader.Loaded loaded, UserConfigStore.UserConfig userConfig) {
        Map<String, String> env = System.getenv();
        return LlmConfigResolver.resolve(
                new LlmConfigResolver.CliOverrides(provider, apiKey, baseUrl, model),
                new LlmConfigResolver.EnvOverrides(
                        env.get("FORGEMIND_PROVIDER"),
                        env.get("FORGEMIND_API_KEY"),
                        env.get("FORGEMIND_BASE_URL"),
                        env.get("FORGEMIND_MODEL")),
                loaded == null ? null : loaded.llm(),
                userConfig,
                null, null);
    }

    /** --config-show：显示最终有效配置（脱敏）。 */
    private void runConfigShow(UserConfigStore userConfigStore) {
        ConfigLoader.Loaded loaded = config != null
                ? configLoader.apply(config)
                : new ConfigLoader.Loaded(AgentConfig.defaults(), null);
        LlmConfig llm = resolveLlm(loaded, userConfigStore.load());
        new ConfigReporter(out).showConfig(llm);
    }

    /** --doctor：只读诊断。返回退出码（0=全部通过，非 0=存在失败）。 */
    private Integer runDoctor(UserConfigStore userConfigStore) {
        ConfigLoader.Loaded loaded = config != null
                ? configLoader.apply(config)
                : new ConfigLoader.Loaded(AgentConfig.defaults(), null);
        Path wd = resolveWorkingDir(workingDir);
        LlmConfig llm;
        try {
            llm = resolveLlm(loaded, userConfigStore.load());
        } catch (com.forgemind.core.exception.ConfigException e) {
            // 配置本身非法（如 custom 缺 baseUrl）→ 显示脱敏错误，不崩溃
            out.println("[FAIL] Configuration");
            out.println("    " + e.getMessage());
            return 1;
        }

        // Tool registry / Permission system 最小只读检查（不执行任何工具）
        com.forgemind.core.tool.InMemoryToolRegistry registry =
                new com.forgemind.core.tool.InMemoryToolRegistry();
        com.forgemind.cli.CliAssembly.standardTools().forEach(registry::register);
        boolean permissionOk = com.forgemind.core.permission.PolicyPermissionManager.withDefaults() != null;

        ConfigReporter.ConnectivityResult connectivity = null;
        if (llm != null && llm.apiKey() != null && !llm.apiKey().isBlank()
                && llm.baseUrl() != null && !llm.baseUrl().isBlank()
                && llm.model() != null && !llm.model().isBlank()) {
            try {
                LlmClient probeClient = llmFactory.apply(llm);
                connectivity = LlmConnectivityProbe.probe(probeClient, llm);
            } catch (RuntimeException e) {
                connectivity = ConfigReporter.ConnectivityResult.failure(
                        LlmConnectivityProbe.sanitize(e.getMessage() == null
                                ? e.toString() : e.getMessage(), llm));
            }
        } else {
            connectivity = ConfigReporter.ConnectivityResult.failure(
                    "LLM connectivity not checked (incomplete configuration)");
        }

        boolean allOk = new ConfigReporter(out).doctor(
                llm, wd, registry, permissionOk, connectivity);
        return allOk ? 0 : 1;
    }

    /** 运行配置向导并保存到用户级配置；保存成功输出路径（不输出 Key）。 */
    private UserConfigStore.UserConfig runConfigure(UserConfigStore store) {
        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
        // 仅真实终端（in == System.in）才隐藏 API Key 输入；测试注入流走 Scanner 明文。
        ConfigWizard wizard = ConfigWizard.fromScanner(scanner, out, in == System.in);
        UserConfigStore.UserConfig current = store.load();
        UserConfigStore.UserConfig result = wizard.run(current);
        if (result == null) {
            out.println("Configuration was not saved (no input / cancelled).");
            return current;
        }
        if (result.provider() == null || result.provider().isBlank()) {
            out.println("Configuration was not saved (invalid provider).");
            return current;
        }
        store.save(result);
        out.println("Configuration saved to:");
        out.println(store.configPath());
        out.println();
        out.println("You can now run:");
        out.println("  forgemind");
        return result;
    }

    /** 是否有有效 LLM 配置（apiKey + baseUrl + model 齐备即可启动）。 */
    private static boolean hasEffectiveConfig(LlmConfig llm, Map<String, String> env) {
        return llm != null && llm.apiKey() != null && !llm.apiKey().isBlank()
                && llm.baseUrl() != null && !llm.baseUrl().isBlank()
                && llm.model() != null && !llm.model().isBlank();
    }

    private static Path resolveWorkingDir(Path dir) {
        return dir == null
                ? Path.of(".").toAbsolutePath().normalize()
                : dir.toAbsolutePath().normalize();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ForgemindCommand()).execute(args);
        System.exit(exitCode);
    }
}
