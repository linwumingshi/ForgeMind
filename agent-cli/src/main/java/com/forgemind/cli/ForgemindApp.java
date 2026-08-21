package com.forgemind.cli;

import com.forgemind.core.Agent;
import com.forgemind.model.AgentResult;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * ForgeMind 运行入口逻辑：单次任务模式（执行后退出）与交互 REPL 模式。
 * 与权限应答共享同一个 {@link Scanner}。
 *
 * <p>M9.4：可观测性摘要 —— 最终状态（success / failed / cancelled）、
 * iterations / toolCalls / subAgents 统计。
 * P2.1：最终答案永远由 {@link AgentResult#finalAnswer()} 完整输出且只输出一次
 * （不再输出 "(streamed above)" 占位；默认模式不实时展示中间 assistant 文本）。</p>
 */
public final class ForgemindApp {

    private final PrintStream out;
    private final Scanner scanner;
    private final StreamingProgressRenderer renderer;

    /** 兼容构造：无渲染器（非 CLI 测试场景）。 */
    public ForgemindApp(PrintStream out, Scanner scanner) {
        this(out, scanner, null);
    }

    /** M9.4：注入渲染器，用于"是否已流式输出"与 SubAgent 计数。 */
    public ForgemindApp(PrintStream out, Scanner scanner, StreamingProgressRenderer renderer) {
        this.out = out;
        this.scanner = scanner;
        this.renderer = renderer;
    }

    /**
     * 运行：task 非空 → 单次任务；否则进入 REPL（输入 exit 退出）。
     */
    public void run(Agent agent, String task, Path workingDir) {
        if (task != null && !task.isBlank()) {
            runOnce(agent, task.trim());
            return;
        }
        repl(agent, workingDir);
    }

    private void repl(Agent agent, Path workingDir) {
        out.println("============================================");
        out.println("             ForgeMind v0.1.0");
        out.println("============================================");
        out.println();
        out.println("Working directory:");
        out.println(workingDir);
        out.println("Type 'exit' to quit.");
        out.println();
        while (true) {
            out.println("You:");
            out.print("> ");
            out.flush();
            String line = scanner.hasNextLine() ? scanner.nextLine() : null;
            if (line == null) {
                return; // EOF
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("exit")) {
                return;
            }
            runOnce(agent, trimmed);
        }
    }

    private void runOnce(Agent agent, String task) {
        // P2.1：每次任务开始重置渲染器展示状态（tool 序号从 1 重新开始，REPL 多轮不续号）
        if (renderer != null) {
            renderer.reset();
        }
        AgentResult result = agent.run(task);
        // P2.4：丢弃 verbose 缓冲的 assistant 文本（最终答案由下方 Final block 完整输出一次）
        if (renderer != null) {
            renderer.finishRun();
        }
        out.println();
        out.println("-- Final answer --");
        // 最终答案永远由 AgentResult.finalAnswer() 完整输出且只输出一次；
        // 不再输出 "(streamed above)" 占位（默认模式不实时展示中间文本）。
        out.println(result.finalAnswer() == null ? "(none)" : result.finalAnswer());
        out.println("status: " + statusOf(result));
        if (!result.finished()) {
            out.println("[not finished] " + (result.error() == null ? "" : result.error()));
        }
        out.println("iterations: " + result.iterations() + "  toolCalls: " + result.toolCallCount()
                + "  subAgents: " + (renderer == null ? 0 : renderer.subAgentCount()));
        out.println();
    }

    private static String statusOf(AgentResult result) {
        if (result.finished()) {
            return "success";
        }
        String error = result.error() == null ? "" : result.error();
        return error.contains("cancelled") ? "cancelled" : "failed";
    }
}
