package com.forgemind.core.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的默认 Tool 注册表实现。
 *
 * <p>{@link #all()} 返回按名称排序的不可修改快照，保证 LLM 提示中工具列表顺序稳定。</p>
 */
public final class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(AgentTool tool) {
        Objects.requireNonNull(tool, "tool");
        AgentTool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("tool already registered: " + tool.name());
        }
    }

    @Override
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Map<String, AgentTool> all() {
        return Collections.unmodifiableMap(new TreeMap<>(tools));
    }

    @Override
    public int size() {
        return tools.size();
    }
}
