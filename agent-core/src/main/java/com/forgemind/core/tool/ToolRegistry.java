package com.forgemind.core.tool;

import java.util.Map;
import java.util.Optional;

/**
 * Tool 注册表：管理已注册工具的发现与查询。Agent 通过它感知可用工具，
 * 但不感知具体 Tool 实现（插件化）。
 */
public interface ToolRegistry {

    /**
     * 注册工具；名称重复时抛出 {@link IllegalArgumentException}。
     */
    void register(AgentTool tool);

    /** 按名称查找；不存在返回 {@link Optional#empty()}。 */
    Optional<AgentTool> find(String name);

    /** 返回只读快照（按名称排序，顺序稳定）。 */
    Map<String, AgentTool> all();

    int size();
}
