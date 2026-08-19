package com.forgemind.cli.config;

import java.io.Console;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * 交互式 LLM 配置向导（M9.5.2.2）。
 *
 * <p>职责：向用户询问 Provider / API Key / Base URL / Model，产出
 * {@link UserConfigStore.UserConfig}；<b>不负责保存</b>（保存由调用方委托
 * {@link UserConfigStore}）。</p>
 *
 * <p>API Key 读取：优先 {@link System#console()} + {@link Console#readPassword()}
 * （不回显）；Console 不可用（IDE / 管道 / 测试）时 fallback 到
 * {@link Scanner#nextLine()} 并明确提示"输入不会隐藏"。任何路径都<b>不打印
 * 用户输入的 Key</b>。</p>
 *
 * <p>交互层与配置数据分离：本类只做输入收集与字段填充，可注入
 * {@link Input}/{@link Output} 便于测试。</p>
 */
public final class ConfigWizard {

    /** 输入抽象（测试可注入假输入）。 */
    public interface Input {
        /** 读取一行文本（EOF 返回 null）。 */
        String readLine();
    }

    /** 输出抽象（测试可捕获）。 */
    public interface Output {
        void println(String line);

        void print(String line);
    }

    private final Input input;
    private final Output output;
    private final Console console;
    /** 仅当输入来自真实终端 System.in 时才允许 console.readPassword 隐藏输入。 */
    private final boolean useConsoleForSecret;
    /** 输入流是否已 EOF（用户取消向导）。 */
    private boolean eof;

    public ConfigWizard(Input input, Output output) {
        this(input, output, System.console());
    }

    /** 显式传入 console（测试可传 null 验证 fallback）。 */
    public ConfigWizard(Input input, Output output, Console console) {
        this(input, output, console, false);
    }

    /** 完整构造：{@code useConsoleForSecret} 仅真实 CLI（System.in 输入）时为 true。 */
    public ConfigWizard(Input input, Output output, Console console, boolean useConsoleForSecret) {
        this.input = input;
        this.output = output;
        this.console = console;
        this.useConsoleForSecret = useConsoleForSecret;
    }

    /**
     * 运行配置向导。
     *
     * @param current 现有配置（可 null = 首次），用于默认值回显
     * @return 用户确认后的配置；<b>输入流 EOF（取消）时返回 null</b>，调用方不应保存
     */
    public UserConfigStore.UserConfig run(UserConfigStore.UserConfig current) {
        UserConfigStore.UserConfig base = current == null
                ? UserConfigStore.UserConfig.empty() : current;

        output.println("ForgeMind LLM configuration");
        output.println("");
        output.print("Provider [deepseek]: ");
        String providerLine = readTrimmed();
        if (eof) {
            return null; // EOF = 用户取消，不保存
        }
        String providerName = providerLine.isEmpty() ? "deepseek" : providerLine;
        LlmProvider provider;
        try {
            provider = LlmProvider.parse(providerName);
        } catch (com.forgemind.core.exception.ConfigException e) {
            output.println("Unknown provider: " + providerName + " (expected openai|deepseek|custom)");
            return base; // 不保存非法输入；调用方可自行处理
        }

        output.print("API Key: ");
        String apiKey = readApiKey();
        if (eof) {
            return null;
        }
        if (apiKey.isEmpty()) {
            apiKey = base.apiKey() == null ? "" : base.apiKey();
        }

        String defaultBaseUrl = provider.defaultBaseUrl() != null
                ? provider.defaultBaseUrl()
                : (base.baseUrl() == null ? "" : base.baseUrl());
        output.print("Base URL [" + defaultBaseUrl + "]: ");
        String baseUrlLine = readTrimmed();
        if (eof) {
            return null;
        }
        String baseUrl = baseUrlLine.isEmpty() ? defaultBaseUrl : baseUrlLine;

        String defaultModel = provider.defaultModel() != null
                ? provider.defaultModel()
                : (base.model() == null ? "" : base.model());
        output.print("Model [" + defaultModel + "]: ");
        String modelLine = readTrimmed();
        if (eof) {
            return null;
        }
        String model = modelLine.isEmpty() ? defaultModel : modelLine;

        // custom：baseUrl/model 必须显式提供
        if (provider == LlmProvider.CUSTOM) {
            if (baseUrl == null || baseUrl.isBlank()) {
                output.println("Custom provider requires a base URL.");
                return base;
            }
            if (model == null || model.isBlank()) {
                output.println("Custom provider requires a model.");
                return base;
            }
        }

        return new UserConfigStore.UserConfig(
                provider.name().toLowerCase(java.util.Locale.ROOT),
                apiKey.isBlank() ? null : apiKey,
                baseUrl == null || baseUrl.isBlank() ? null : baseUrl,
                model == null || model.isBlank() ? null : model,
                base.connectTimeout(), base.readTimeout());
    }

    /** 读取 API Key：Console 优先（不回显），否则 Scanner 行读并提示。 */
    private String readApiKey() {
        if (console != null && useConsoleForSecret) {
            char[] chars = console.readPassword();
            if (chars == null) {
                eof = true;
                return "";
            }
            return new String(chars);
        }
        output.println("(input will not be hidden: no console available)");
        return readLineSafe();
    }

    /** EOF 安全的行读取：EOF 置 eof 标志并返回空串，避免管道/自动化下崩溃。 */
    private String readLineSafe() {
        String line = input.readLine();
        if (line == null) {
            eof = true;
            return "";
        }
        return line.trim();
    }

    private String readTrimmed() {
        return readLineSafe();
    }

    // ---------- 便利工厂（真实 CLI 使用） ----------

    /** 从 Scanner/PrintStream 构造向导（仅真实终端 System.in 时隐藏 API Key）。 */
    public static ConfigWizard fromScanner(Scanner scanner, PrintStream out, boolean isSystemIn) {
        return new ConfigWizard(scannerInput(scanner), new StdOutput(out), System.console(), isSystemIn);
    }

    /** EOF 安全：Scanner.nextLine() 在 EOF 抛 NoSuchElementException，转为 null。 */
    private static Input scannerInput(Scanner scanner) {
        return () -> {
            try {
                return scanner.hasNextLine() ? scanner.nextLine() : null;
            } catch (RuntimeException e) {
                return null; // EOF / 关闭
            }
        };
    }

    private static final class StdOutput implements Output {
        private final PrintStream out;

        StdOutput(PrintStream out) {
            this.out = out;
        }

        @Override
        public void println(String line) {
            out.println(line);
        }

        @Override
        public void print(String line) {
            out.print(line);
        }
    }
}
