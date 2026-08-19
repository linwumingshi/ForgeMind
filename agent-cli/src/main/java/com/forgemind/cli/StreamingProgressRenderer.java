package com.forgemind.cli;

import com.forgemind.core.loop.ProgressListener;
import java.io.PrintStream;
import java.util.Objects;

/**
 * CLI 流式渲染器：把 {@link ProgressListener} 事件渲染为终端增量输出。
 *
 * <ul>
 *   <li>{@code onTextDelta}：文本增量实时打印 + flush（逐字符/逐段可见，UTF-8）；</li>
 *   <li>{@code onToolCallStarted} / {@code onToolResult}：{@code [tool: name] [success]/[failed]}；</li>
 *   <li>{@code onSubAgentStarted} / {@code onSubAgentResult}：{@code [subagent:start] task [complete]/[failed]}。</li>
 * </ul>
 *
 * <p>纯观察层：不参与 AgentLoop 决策、不执行 Tool、不写 Context。兼容性：
 * 非 Streaming 的 {@code chat()} LLM 同样可用 —— 此时无文本增量（
 * {@link #hasStreamedText()}=false），但 Tool/SubAgent 事件仍正常展示，
 * 最终答案由 {@link ForgemindApp} 完整输出，不重复。</p>
 */
public final class StreamingProgressRenderer implements ProgressListener {

    /** SubAgent 任务展示长度上限（超长截断，避免刷屏）。 */
    private static final int MAX_TASK_DISPLAY = 80;

    private final PrintStream out;
    private int subAgentCount;
    private boolean streamedText;
    /** 当前输出行是否已有内容（用于在事件前补换行，避免连续事件产生空行）。 */
    private boolean lineHasContent;

    public StreamingProgressRenderer(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void onTextDelta(String delta) {
        streamedText = true;
        out.print(delta);
        lineHasContent = true;
        out.flush();
    }

    @Override
    public void onToolCallStarted(String toolName) {
        beginEventLine("[tool: " + toolName + "] ");
    }

    @Override
    public void onToolResult(String toolName, boolean success) {
        out.println(success ? "[success]" : "[failed]");
        lineHasContent = false;
        out.flush();
    }

    @Override
    public void onSubAgentStarted(String task) {
        subAgentCount++;
        beginEventLine("[subagent:start] " + truncate(task) + " ");
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

    /** 是否已输出过文本增量（streaming 模式）。供 ForgemindApp 避免重复 final answer。 */
    public boolean hasStreamedText() {
        return streamedText;
    }

    /** 本 Agent 运行中启动的 SubAgent 总数（创建了几个 SubAgent）。 */
    public int subAgentCount() {
        return subAgentCount;
    }

    private static String truncate(String task) {
        if (task == null || task.length() <= MAX_TASK_DISPLAY) {
            return task;
        }
        return task.substring(0, MAX_TASK_DISPLAY) + "...";
    }
}
