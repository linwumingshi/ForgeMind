package com.forgemind.cli;

import com.forgemind.core.loop.ProgressListener;
import com.forgemind.model.ToolResult;
import java.io.PrintStream;
import java.util.Objects;

/**
 * CLI 流式渲染器：把 {@link ProgressListener} 事件渲染为终端增量输出。
 *
 * <p><b>默认模式（verbose=false，P2.1）：</b></p>
 * <ul>
 *   <li>{@code onTextDelta}：<b>不输出</b> —— 带 ToolCall 的 assistant 中间文本
 *       属于中间过程，最终答案统一由 {@link ForgemindApp} 在 {@code agent.run()}
 *       完成后完整输出（不再出现 "(streamed above)"）；</li>
 *   <li>{@code onToolCallStarted} / {@code onToolResult}：按序号展示 Tool 进度，
 *       成功 {@code [1] read_file ✓}，失败 {@code [2] shell ✗ exit=1}（有
 *       exitCode 时）+ 一行简短 stderr 摘要；</li>
 *   <li>{@code onSubAgentStarted} / {@code onSubAgentResult}：
 *       {@code [subagent:start] task [complete]/[failed]}。</li>
 * </ul>
 *
 * <p><b>verbose=true（P2.4 接线 CLI --verbose）：</b>恢复 assistant text delta 实时输出
 * （逐字符/逐段可见，UTF-8），并在每个 Tool 结果后缩进展示完整 tool output
 * （含 stdout 与 [stderr] 分节；行数/字符双上限截断，避免刷屏）。</p>
 *
 * <p>纯观察层：不参与 AgentLoop 决策、不执行 Tool、不写 Context。兼容性：
 * 非 Streaming 的 {@code chat()} LLM 同样可用 —— 此时无文本增量，但
 * Tool/SubAgent 事件仍正常展示，最终答案由 {@link ForgemindApp} 完整输出，不重复。</p>
 */
public final class StreamingProgressRenderer implements ProgressListener {

    /** SubAgent 任务展示长度上限（超长截断，避免刷屏）。 */
    private static final int MAX_TASK_DISPLAY = 80;

    /** 失败时 stderr 摘要的最大展示长度（超长截断）。 */
    private static final int MAX_STDERR_DISPLAY = 120;

    /** verbose 模式下 tool output 的最大展示行数（超长截断，避免刷屏）。 */
    private static final int MAX_VERBOSE_OUTPUT_LINES = 120;

    /** verbose 模式下 tool output 的最大展示字符数（超长截断）。 */
    private static final int MAX_VERBOSE_OUTPUT_CHARS = 8 * 1024;

    /** verbose 模式下 tool output 的缩进（与事件行区分）。 */
    private static final String OUTPUT_INDENT = "    ";

    /** 成功标记。 */
    private static final String OK = "\u2713";   // ✓

    /** 失败标记。 */
    private static final String FAIL = "\u2717"; // ✗

    private final PrintStream out;
    /** 本 Agent 运行内 Tool 调用序号（REPL 多轮任务经 {@link #reset()} 重新从 1 开始）。 */
    private int toolSequence;
    /** verbose 模式：true 时缓冲并展示 assistant 中间文本与完整 tool output。 */
    private boolean verbose;
    /** verbose 模式缓冲的 assistant 文本增量（工具开始时输出；最终答案轮由 {@link #finishRun()} 丢弃）。 */
    private final StringBuilder pendingDelta = new StringBuilder();
    private int subAgentCount;
    private boolean streamedText;
    /** 当前输出行是否已有内容（用于在事件前补换行，避免连续事件产生空行）。 */
    private boolean lineHasContent;

    public StreamingProgressRenderer(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    /** 设置 verbose 模式：true 时恢复 assistant text delta 实时输出；默认 false。 */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /** 重置本 Agent 运行内的展示状态（tool 序号重新从 1 开始）。REPL 多轮任务间调用。 */
    public void reset() {
        toolSequence = 0;
        pendingDelta.setLength(0);
    }

    /**
     * Agent 单次运行结束（P2.4）：丢弃缓冲的 assistant 文本。
     *
     * <p>带 ToolCall 的轮次，其 assistant 文本属于中间过程，在
     * {@link #onToolCallStarted} 时输出；<b>最终答案轮的 delta 不在此列</b> ——
     * 最终答案由 {@link ForgemindApp} 在 {@code agent.run()} 后完整输出一次。
     * 因此运行结束后必须清空缓冲，避免最终答案 delta 被后续事件输出造成重复。</p>
     */
    public void finishRun() {
        pendingDelta.setLength(0);
    }

    @Override
    public void onTextDelta(String delta) {
        // 默认模式不展示中间 assistant 文本（最终答案由 ForgemindApp 统一输出）；
        // verbose 模式缓冲 delta，在工具开始时输出（避免最终答案 delta 与 Final block 重复）。
        streamedText = true;
        if (verbose) {
            pendingDelta.append(delta);
        }
    }

    @Override
    public void onToolCallStarted(String toolName) {
        // 工具开始前，把缓冲的中间 assistant 文本输出（verbose 模式才有内容）
        if (pendingDelta.length() > 0) {
            if (lineHasContent) {
                out.println();
            }
            out.print(pendingDelta);
            pendingDelta.setLength(0);
            lineHasContent = true;
            out.flush();
        }
        toolSequence++;
        beginEventLine("[" + toolSequence + "] " + toolName + " ");
    }

    @Override
    public void onToolResult(String toolName, boolean success) {
        out.println(success ? OK : FAIL);
        lineHasContent = false;
        out.flush();
    }

    @Override
    public void onToolResult(String toolName, ToolResult result) {
        if (result.success()) {
            out.println(OK);
        } else {
            Integer exitCode = result.exitCode();
            out.println(exitCode == null ? FAIL : FAIL + " exit=" + exitCode);
            String stderr = firstMeaningfulStderrLine(result);
            if (stderr != null) {
                out.println("  stderr: " + truncate(stderr, MAX_STDERR_DISPLAY));
            }
        }
        // P2.4：verbose 模式展示完整 tool output（缩进 + 截断），默认模式不展示
        if (verbose) {
            printToolOutput(result);
        }
        lineHasContent = false;
        out.flush();
    }

    /** verbose 模式：缩进打印 tool output（含 stdout 与 [stderr] 分节），超长截断防刷屏。 */
    private void printToolOutput(ToolResult result) {
        if (result == null || result.output() == null || result.output().isEmpty()) {
            return;
        }
        String output = result.output();
        String[] lines = output.split("\\r?\\n");
        int lineCount = Math.min(lines.length, MAX_VERBOSE_OUTPUT_LINES);
        int chars = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            String line = lines[i];
            if (chars + line.length() > MAX_VERBOSE_OUTPUT_CHARS) {
                sb.append(OUTPUT_INDENT).append("[output truncated]\n");
                out.print(sb);
                return;
            }
            sb.append(OUTPUT_INDENT).append(line).append('\n');
            chars += line.length();
        }
        if (lines.length > lineCount) {
            sb.append(OUTPUT_INDENT).append("[output truncated: ").append(lines.length - lineCount)
                    .append(" more lines]\n");
        }
        out.print(sb);
    }

    @Override
    public void onSubAgentStarted(String task) {
        subAgentCount++;
        beginEventLine("[subagent:start] " + truncate(task, MAX_TASK_DISPLAY) + " ");
    }

    @Override
    public void onSubAgentResult(String task, boolean success) {
        out.println(success ? "[complete]" : "[failed]");
        lineHasContent = false;
        out.flush();
    }

    /** 事件行开始：若当前行已有内容则先换行（避免接在 delta/结果后）。 */
    private void beginEventLine(String prefix) {
        if (lineHasContent) {
            out.println();
        }
        out.print(prefix);
        lineHasContent = true;
        out.flush();
    }

    /** 是否已收到过文本增量（streaming 通道被使用）。 */
    public boolean hasStreamedText() {
        return streamedText;
    }

    /** 本 Agent 运行中启动的 SubAgent 总数（创建了几个 SubAgent）。 */
    public int subAgentCount() {
        return subAgentCount;
    }

    /**
     * 从失败 ToolResult 的 output 中提取 stderr 分节（{@code [stderr]} 标记后）
     * 的首行有意义内容；不存在则返回 null。只取摘要，不打印完整 ToolResult。
     */
    private static String firstMeaningfulStderrLine(ToolResult result) {
        if (result == null || result.output() == null) {
            return null;
        }
        String marker = "[stderr]";
        int idx = result.output().lastIndexOf(marker);
        if (idx < 0) {
            return null;
        }
        String tail = result.output().substring(idx + marker.length());
        for (String line : tail.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /** 超长截断（追加 {@code ...}）。 */
    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
