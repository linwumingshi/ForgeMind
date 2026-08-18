package com.forgemind.cli;

import com.forgemind.core.Agent;
import com.forgemind.model.AgentResult;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * ForgeMind 运行入口逻辑：单次任务模式（执行后退出）与交互 REPL 模式。
 * 与权限应答共享同一个 {@link Scanner}。
 */
public final class ForgemindApp {

    private final PrintStream out;
    private final Scanner scanner;

    public ForgemindApp(PrintStream out, Scanner scanner) {
        this.out = out;
        this.scanner = scanner;
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
        AgentResult result = agent.run(task);
        out.println();
        out.println("-- Final answer --");
        out.println(result.finalAnswer() == null ? "(none)" : result.finalAnswer());
        if (!result.finished()) {
            out.println("[not finished] " + (result.error() == null ? "" : result.error()));
        }
        out.println("iterations: " + result.iterations() + "  toolCalls: " + result.toolCallCount());
        out.println();
    }
}
