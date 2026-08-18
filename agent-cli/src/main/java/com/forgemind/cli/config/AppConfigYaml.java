package com.forgemind.cli.config;

import com.forgemind.core.config.ToolLimits;
import java.time.Duration;
import java.util.List;

/**
 * YAML 绑定层（Jackson 反序列化目标，全包装类型）：
 * 缺省字段在 {@link ConfigLoader} 映射阶段用领域模型默认值填充，
 * 避免 primitive 默认 0 触发 ToolLimits 校验误报。
 *
 * @param agent agent 配置节
 * @param llm   llm 配置节
 */
record AppConfigYaml(AgentYaml agent, LlmYaml llm) {

    record AgentYaml(Integer maxIterations, ToolLimitsYaml toolLimits) {
    }

    /** 与 {@link ToolLimits} 字段一一对应（可部分填写）。 */
    record ToolLimitsYaml(
            Integer listFilesMaxEntries,
            Integer listFilesMaxDepth,
            Long readFileMaxBytes,
            Long editFileOldTextMaxBytes,
            Integer searchMaxResults,
            Long searchMaxFileBytes,
            List<String> searchIgnoreDirs,
            Long outputLimit,
            String shellTimeout,
            String shellType) {
    }

    /** 与 {@link com.forgemind.core.config.LlmConfig} 对应；timeout 支持 ISO-8601 或秒数。 */
    record LlmYaml(
            String baseUrl,
            String apiKey,
            String model,
            String connectTimeout,
            String readTimeout) {

        static Duration parseDuration(String value) {
            try {
                return Duration.parse(value);
            } catch (RuntimeException e) {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
            }
        }
    }
}
