package com.forgemind.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.config.LlmConfig;
import com.forgemind.core.config.ToolLimits;
import com.forgemind.core.exception.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 配置加载器（不使用 Spring @ConfigurationProperties）。
 *
 * <p>流程：读取文件 → ${ENV_VAR} 环境变量展开 → Jackson YAML 反序列化绑定层
 * → 映射为领域配置（缺省字段用默认值）。加载失败给出明确错误；
 * 错误信息中绝不出现 API Key 值。</p>
 */
public final class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** 加载结果：领域配置对。 */
    public record Loaded(AgentConfig agent, LlmConfig llm) {
    }

    private ConfigLoader() {
    }

    /** 使用系统环境变量加载。 */
    public static Loaded load(Path configFile) {
        return load(configFile, System.getenv());
    }

    /** 使用指定环境变量映射加载（测试可注入）。 */
    public static Loaded load(Path configFile, Map<String, String> env) {
        String raw;
        try {
            raw = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(
                    "failed to read config file '" + configFile + "': " + e.getMessage(), e);
        }
        String expanded = expandEnv(raw, env);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        AppConfigYaml yaml;
        try {
            yaml = mapper.readValue(expanded, AppConfigYaml.class);
        } catch (IOException e) {
            throw new ConfigException(
                    "invalid YAML in config file '" + configFile + "': " + e.getMessage(), e);
        }
        return mapToDomain(yaml);
    }

    private static String expandEnv(String raw, Map<String, String> env) {
        Matcher matcher = ENV_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String var = matcher.group(1);
            String value = env.get(var);
            if (value == null) {
                throw new ConfigException("environment variable " + var + " is not set");
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static Loaded mapToDomain(AppConfigYaml yaml) {
        AgentConfig agent = mapAgent(yaml.agent());
        LlmConfig llm = mapLlm(yaml.llm());
        return new Loaded(agent, llm);
    }

    private static AgentConfig mapAgent(AppConfigYaml.AgentYaml yaml) {
        if (yaml == null) {
            return AgentConfig.defaults();
        }
        int maxIterations = yaml.maxIterations() != null
                ? yaml.maxIterations() : AgentConfig.DEFAULT_MAX_ITERATIONS;
        ToolLimits toolLimits = mapToolLimits(yaml.toolLimits());
        return new AgentConfig(maxIterations, toolLimits);
    }

    private static ToolLimits mapToolLimits(AppConfigYaml.ToolLimitsYaml yaml) {
        if (yaml == null) {
            return ToolLimits.defaults();
        }
        ToolLimits d = ToolLimits.defaults();
        return new ToolLimits(
                yaml.listFilesMaxEntries() != null ? yaml.listFilesMaxEntries() : d.listFilesMaxEntries(),
                yaml.listFilesMaxDepth() != null ? yaml.listFilesMaxDepth() : d.listFilesMaxDepth(),
                yaml.readFileMaxBytes() != null ? yaml.readFileMaxBytes() : d.readFileMaxBytes(),
                yaml.editFileOldTextMaxBytes() != null ? yaml.editFileOldTextMaxBytes() : d.editFileOldTextMaxBytes(),
                yaml.searchMaxResults() != null ? yaml.searchMaxResults() : d.searchMaxResults(),
                yaml.searchMaxFileBytes() != null ? yaml.searchMaxFileBytes() : d.searchMaxFileBytes(),
                yaml.searchIgnoreDirs() != null ? yaml.searchIgnoreDirs() : d.searchIgnoreDirs(),
                yaml.outputLimit() != null ? yaml.outputLimit() : d.outputLimit(),
                yaml.shellTimeout() != null
                        ? AppConfigYaml.LlmYaml.parseDuration(yaml.shellTimeout()) : d.shellTimeout(),
                yaml.shellType() != null
                        ? com.forgemind.core.config.ShellType.valueOf(yaml.shellType().toUpperCase())
                        : d.shellType());
    }

    private static LlmConfig mapLlm(AppConfigYaml.LlmYaml yaml) {
        if (yaml == null) {
            return LlmConfig.defaults();
        }
        LlmConfig d = LlmConfig.defaults();
        return new LlmConfig(
                yaml.baseUrl() != null ? yaml.baseUrl() : d.baseUrl(),
                yaml.apiKey(),
                yaml.model() != null ? yaml.model() : d.model(),
                yaml.connectTimeout() != null
                        ? AppConfigYaml.LlmYaml.parseDuration(yaml.connectTimeout()) : d.connectTimeout(),
                yaml.readTimeout() != null
                        ? AppConfigYaml.LlmYaml.parseDuration(yaml.readTimeout()) : d.readTimeout());
    }
}
