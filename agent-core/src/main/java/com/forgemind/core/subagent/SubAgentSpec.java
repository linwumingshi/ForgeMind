package com.forgemind.core.subagent;

import com.forgemind.core.exception.ConfigException;
import java.util.List;

/**
 * SubAgent 规格（M9）：描述一次子 Agent 编排请求。
 *
 * <p>字段语义（与主 Agent 的关系）：</p>
 * <ul>
 *   <li>{@code task}：子任务自然语言描述（必填，非空白）；</li>
 *   <li>{@code tools}：请求的工具白名单（null/空 = 继承主 registry 全部）；
 *       非空时每一项必须非空白，且最终白名单必须 ⊆ 主 registry（M9.2 强制）；</li>
 *   <li>{@code maxIterations}：子迭代预算（null = 继承主 config；非 null 必须为正）。</li>
 * </ul>
 *
 * <p>不含 depth：M9 深度固定为 1（子 Agent 不可再创建子 Agent，白名单构建时
 * 强制排除 sub_agent，见 M9.2 设计）。</p>
 */
public record SubAgentSpec(
        String task,
        List<String> tools,
        Integer maxIterations) {

    public SubAgentSpec {
        if (task == null || task.isBlank()) {
            throw new ConfigException("subagent task must not be blank");
        }
        if (tools != null) {
            tools = List.copyOf(tools);
            for (String tool : tools) {
                if (tool == null || tool.isBlank()) {
                    throw new ConfigException("subagent tools must not contain blank names");
                }
            }
        }
        if (maxIterations != null && maxIterations <= 0) {
            throw new ConfigException("subagent maxIterations must be positive: " + maxIterations);
        }
    }

    /** 便利工厂：仅任务，其余继承。 */
    public static SubAgentSpec of(String task) {
        return new SubAgentSpec(task, null, null);
    }

    /** 便利工厂：任务 + 白名单，其余继承。 */
    public static SubAgentSpec of(String task, List<String> tools) {
        return new SubAgentSpec(task, tools, null);
    }

    /** tools 是否为"继承全部"（null/空）。 */
    public boolean inheritsAllTools() {
        return tools == null || tools.isEmpty();
    }
}
