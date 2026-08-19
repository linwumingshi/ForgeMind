package com.forgemind.cli.config;

import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.tool.ToolRegistry;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 配置诊断输出（M9.5.2.3）：{@code --config-show} 与 {@code --doctor}。
 *
 * <p><b>安全不变量：任何输出路径都不得包含完整 API Key、Authorization header、
 * Bearer token、Key 长度/前缀/后缀/hash。</b>API Key 只允许显示为
 * {@code configured} / {@code not configured}。</p>
 */
public final class ConfigReporter {

    private final PrintStream out;

    public ConfigReporter(PrintStream out) {
        this.out = out;
    }

    /** 显示最终有效配置（复用 resolver 的合并结果；绝不显示 Key 值）。 */
    public void showConfig(LlmConfig llm) {
        out.println("Provider: " + providerName(llm));
        out.println("Base URL: " + (llm.baseUrl() == null ? "(not configured)" : llm.baseUrl()));
        out.println("Model: " + (llm.model() == null ? "(not configured)" : llm.model()));
        out.println("API Key: " + (hasKey(llm) ? "configured" : "not configured"));
    }

    /**
     * 运行 doctor：只读诊断，不修改任何文件、不执行用户代码。
     *
     * @param llm       合并后的最终配置（可 null = 完全无配置）
     * @param workingDir 工作目录
     * @param registry   工具注册表（用于 registry 检查）
     * @param permissionOk 权限系统初始化是否正常（由调用方判定）
     * @param connectivity LLM 连通性探针（返回 null = 通过；否则为失败原因）
     * @return 全部通过 = true
     */
    public boolean doctor(LlmConfig llm, Path workingDir, ToolRegistry registry,
                          boolean permissionOk, ConnectivityResult connectivity) {
        boolean ok = true;

        ok &= report("Configuration", llm != null);
        boolean keyOk = hasKey(llm);
        ok &= report("API Key", keyOk, keyOk ? null : "API key is not configured");
        boolean baseOk = llm != null && llm.baseUrl() != null && !llm.baseUrl().isBlank();
        ok &= report("Base URL", baseOk, baseOk ? null : "Base URL is not configured");
        boolean modelOk = llm != null && llm.model() != null && !llm.model().isBlank();
        ok &= report("Model", modelOk, modelOk ? null : "Model is not configured");

        boolean wdOk = workingDir != null && Files.isDirectory(workingDir);
        ok &= report("Working directory", wdOk, wdOk ? null : "Directory does not exist: " + workingDir);

        ok &= report("Tool registry", registry != null && registry.size() > 0);
        ok &= report("Permission system", permissionOk);

        if (connectivity == null) {
            ok &= report("LLM connectivity", true);
        } else {
            ok &= reportConnectivity(connectivity);
            ok = false;
        }
        return ok;
    }

    /** 连通性结果（脱敏后展示；reason 必须已由探针过滤）。 */
    public record ConnectivityResult(String status, String reason) {
        public static ConnectivityResult failure(String reason) {
            return new ConnectivityResult(null, reason);
        }

        public static ConnectivityResult httpFailure(int status, String reason) {
            return new ConnectivityResult("HTTP status: " + status, reason);
        }
    }

    private boolean report(String name, boolean pass, String detail) {
        // 用 ASCII 标记 [OK]/[FAIL]：避免 Windows GBK 控制台把 ✓/✗ 渲染成 "?"。
        out.println((pass ? "[OK] " : "[FAIL] ") + name);
        if (!pass && detail != null) {
            out.println("    " + detail);
        }
        return pass;
    }

    private boolean report(String name, boolean pass) {
        return report(name, pass, null);
    }

    private boolean reportConnectivity(ConnectivityResult c) {
        out.println("[FAIL] LLM connectivity");
        if (c.status() != null) {
            out.println("    " + c.status());
        }
        if (c.reason() != null && !c.reason().isBlank()) {
            out.println("    Reason: " + c.reason());
        }
        return false;
    }

    private static boolean hasKey(LlmConfig llm) {
        return llm != null && llm.apiKey() != null && !llm.apiKey().isBlank();
    }

    /** 显示 Provider 名（从 baseUrl 推断；仅供展示）。 */
    private static String providerName(LlmConfig llm) {
        if (llm == null || llm.baseUrl() == null) {
            return "(not configured)";
        }
        String url = llm.baseUrl();
        if (url.contains("deepseek")) {
            return "deepseek";
        }
        if (url.contains("openai")) {
            return "openai";
        }
        return "custom";
    }
}
