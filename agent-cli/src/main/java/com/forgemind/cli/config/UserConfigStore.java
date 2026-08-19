package com.forgemind.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.forgemind.core.exception.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户级 LLM 配置存储（M9.5.2.2）。
 *
 * <p>路径：{@code user.home/.forgemind/config.yml}（Windows 为
 * {@code %USERPROFILE%\.forgemind\config.yml}，经 {@code System.getProperty("user.home")}
 * 统一获取，不硬编码平台路径）。</p>
 *
 * <p>职责仅限：确定路径、读取、保存、必要目录创建。保存采用
 * <b>临时文件 + 原子 move</b>，避免异常中断留下半截 YAML。</p>
 *
 * <p>安全：{@link #apiKey()} 允许保存明文（用户级文件），但本类<b>绝不输出完整 Key</b>；
 * 错误信息不携带 Key 值。</p>
 */
public final class UserConfigStore {

    private static final Pattern ENV_PATTERN =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final Path configPath;

    /** 使用真实用户主目录。 */
    public UserConfigStore() {
        this(defaultConfigPath());
    }

    /** 指定配置路径（测试注入临时目录）。 */
    public UserConfigStore(Path configPath) {
        this.configPath = configPath;
    }

    /** 用户级配置默认路径：user.home/.forgemind/config.yml。 */
    public static Path defaultConfigPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new ConfigException("cannot determine user home directory");
        }
        return Path.of(home, ".forgemind", "config.yml");
    }

    /** 当前配置路径。 */
    public Path configPath() {
        return configPath;
    }

    /** 配置目录是否存在。 */
    public boolean exists() {
        return Files.exists(configPath);
    }

    /** 读取用户级配置；文件不存在时返回 {@link UserConfig}（全空）。 */
    public UserConfig load() {
        return load(System.getenv());
    }

    /** 指定环境变量映射读取（测试可注入）。 */
    public UserConfig load(java.util.Map<String, String> env) {
        if (!Files.exists(configPath)) {
            return UserConfig.empty();
        }
        String raw;
        try {
            raw = Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(
                    "failed to read user config '" + configPath + "': " + e.getMessage(), e);
        }
        String expanded = expandEnv(raw, env);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        UserConfigYaml yaml;
        try {
            yaml = mapper.readValue(expanded, UserConfigYaml.class);
        } catch (IOException e) {
            throw new ConfigException(
                    "invalid user config YAML '" + configPath + "': " + e.getMessage(), e);
        }
        return yaml.toDomain();
    }

    /**
     * 保存用户级配置（临时文件 + 原子 move；目录自动创建）。
     * 不打印任何 Key 值。
     */
    public void save(UserConfig config) {
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(tmp, config.toYaml(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ConfigException(
                    "failed to save user config '" + configPath + "': " + e.getMessage(), e);
        }
    }

    private static String expandEnv(String raw, java.util.Map<String, String> env) {
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

    // ---------- 领域模型 ----------

    /**
     * 用户级配置领域模型（不可变）。字段全可空。
     */
    public record UserConfig(String provider, String apiKey, String baseUrl, String model,
                             Duration connectTimeout, Duration readTimeout) {

        public static UserConfig empty() {
            return new UserConfig(null, null, null, null, null, null);
        }

        public UserConfig withProvider(String value) {
            return new UserConfig(value, apiKey, baseUrl, model, connectTimeout, readTimeout);
        }

        public UserConfig withApiKey(String value) {
            return new UserConfig(provider, value, baseUrl, model, connectTimeout, readTimeout);
        }

        public UserConfig withBaseUrl(String value) {
            return new UserConfig(provider, apiKey, value, model, connectTimeout, readTimeout);
        }

        public UserConfig withModel(String value) {
            return new UserConfig(provider, apiKey, baseUrl, value, connectTimeout, readTimeout);
        }

        /** 是否"看起来完整"（provider/baseUrl/model/apiKey 均非空）——用于跳过首次向导。 */
        public boolean isComplete() {
            return provider != null && !provider.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }

        /** 序列化为 YAML 文本（apiKey 原样保存，但本方法不打印）。 */
        String toYaml() {
            StringBuilder sb = new StringBuilder();
            sb.append("provider: ").append(provider == null ? "" : provider).append('\n');
            sb.append("baseUrl: ").append(baseUrl == null ? "" : baseUrl).append('\n');
            sb.append("model: ").append(model == null ? "" : model).append('\n');
            sb.append("apiKey: ").append(apiKey == null ? "" : apiKey).append('\n');
            if (connectTimeout != null) {
                sb.append("connectTimeout: ").append(connectTimeout).append('\n');
            }
            if (readTimeout != null) {
                sb.append("readTimeout: ").append(readTimeout).append('\n');
            }
            return sb.toString();
        }
    }

    /** YAML 绑定层（包装类型，缺省为 null）。 */
    private record UserConfigYaml(String provider, String apiKey, String baseUrl, String model,
                                  String connectTimeout, String readTimeout) {

        UserConfig toDomain() {
            return new UserConfig(
                    provider,
                    apiKey,
                    baseUrl,
                    model,
                    connectTimeout == null || connectTimeout.isBlank()
                            ? null : parseDuration(connectTimeout),
                    readTimeout == null || readTimeout.isBlank()
                            ? null : parseDuration(readTimeout));
        }

        private static Duration parseDuration(String value) {
            try {
                return Duration.parse(value);
            } catch (RuntimeException e) {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
            }
        }
    }
}
