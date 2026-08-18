package com.forgemind.cli;

import com.forgemind.cli.config.ConfigLoader;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.llm.openai.OpenAiCompatibleLlmClient;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    @Option(names = "--config", description = "Path to YAML config file")
    private Path config;

    @Parameters(index = "0", arity = "0..1",
            description = "Task to run once (omit to enter interactive REPL)")
    private String task;

    private final Function<LlmConfig, LlmClient> llmFactory;
    private final Function<Path, ConfigLoader.Loaded> configLoader;
    private final PrintStream out;
    private final InputStream in;

    public ForgemindCommand() {
        this(defaultLlmFactory(), ConfigLoader::load, System.out, System.in);
    }

    ForgemindCommand(Function<LlmConfig, LlmClient> llmFactory,
                     Function<Path, ConfigLoader.Loaded> configLoader,
                     PrintStream out, InputStream in) {
        this.llmFactory = llmFactory;
        this.configLoader = configLoader;
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
        ConfigLoader.Loaded loaded = config != null
                ? configLoader.apply(config)
                : new ConfigLoader.Loaded(AgentConfig.defaults(), LlmConfig.defaults());
        Path wd = resolveWorkingDir(workingDir);

        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
        PermissionAnswerer answerer = yes
                ? request -> true
                : new InteractivePermissionAnswerer(out, scanner);
        LlmClient llm = llmFactory.apply(loaded.llm());
        // M8.5：CLI 观察层 —— 文本增量实时输出 + Tool 调用/结果展示；
        // chat()（非流式）同样兼容（无文本增量，Tool 展示与最终答案不变）。
        com.forgemind.core.Agent agent = CliAssembly.buildAgent(
                loaded.agent(), llm, wd, answerer, new StreamingProgressRenderer(out));
        new ForgemindApp(out, scanner).run(agent, task, wd);
        return 0;
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
