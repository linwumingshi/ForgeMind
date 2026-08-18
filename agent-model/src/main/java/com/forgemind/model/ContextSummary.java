package com.forgemind.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 历史上下文摘要（纯数据，不可变）。
 *
 * <p>用于 Context 压缩时保留 Coding Agent 最重要的状态；<b>不保存完整历史消息、
 * 不包含敏感信息</b>。字段可空；渲染为 {@code [CONTEXT SUMMARY]...[/CONTEXT SUMMARY]}
 * 文本，避免模型把摘要误认为新用户任务。</p>
 *
 * @param task         用户任务
 * @param facts        重要事实（M7 暂不自动提取，留空）
 * @param modifiedFiles 修改过的文件
 * @param commands     执行过的命令
 * @param testResults  测试结果摘要
 * @param pendingWork  待办（M7 暂不自动提取，留空）
 * @param decisions    决策（M7 暂不自动提取，留空）
 */
public record ContextSummary(
        String task,
        List<String> facts,
        List<String> modifiedFiles,
        List<String> commands,
        List<String> testResults,
        List<String> pendingWork,
        List<String> decisions) {

    public ContextSummary {
        Objects.requireNonNull(task, "task");
        facts = defensiveCopy(facts);
        modifiedFiles = defensiveCopy(modifiedFiles);
        commands = defensiveCopy(commands);
        testResults = defensiveCopy(testResults);
        pendingWork = defensiveCopy(pendingWork);
        decisions = defensiveCopy(decisions);
    }

    private static List<String> defensiveCopy(List<String> list) {
        if (list == null) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static ContextSummary empty() {
        return new ContextSummary("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return (task == null || task.isBlank())
                && facts.isEmpty() && modifiedFiles.isEmpty() && commands.isEmpty()
                && testResults.isEmpty() && pendingWork.isEmpty() && decisions.isEmpty();
    }

    /** 渲染为带标记的摘要文本（只输出非空字段）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("[CONTEXT SUMMARY]\n");
        if (task != null && !task.isBlank()) {
            sb.append("task: ").append(task).append('\n');
        }
        appendList(sb, "facts", facts);
        appendList(sb, "modifiedFiles", modifiedFiles);
        appendList(sb, "commands", commands);
        appendList(sb, "testResults", testResults);
        appendList(sb, "pendingWork", pendingWork);
        appendList(sb, "decisions", decisions);
        sb.append("[/CONTEXT SUMMARY]");
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(label).append(": ").append(values).append('\n');
    }
}
