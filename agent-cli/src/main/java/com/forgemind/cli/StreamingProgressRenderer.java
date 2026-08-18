package com.forgemind.cli;

import com.forgemind.core.loop.ProgressListener;
import java.io.PrintStream;
import java.util.Objects;

/**
 * CLI 流式渲染器：把 {@link ProgressListener} 事件渲染为终端增量输出。
 *
 * <ul>
 *   <li>{@code onTextDelta}：文本增量实时打印 + flush（逐字符/逐段可见）；</li>
 *   <li>{@code onToolCallStarted}：打印 {@code [tool: name] }；</li>
 *   <li>{@code onToolResult}：追加 {@code [success]} / {@code [failed]}。</li>
 * </ul>
 *
 * <p>纯观察层：不参与 AgentLoop 决策、不执行 Tool、不写 Context。兼容性：
 * 非 Streaming 的 {@code chat()} LLM 同样可用 —— 此时无文本增量，但 Tool
 * 调用/结果仍正常展示，最终答案仍由 {@link ForgemindApp} 统一输出。</p>
 */
public final class StreamingProgressRenderer implements ProgressListener {

    private final PrintStream out;

    public StreamingProgressRenderer(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void onTextDelta(String delta) {
        out.print(delta);
        out.flush();
    }

    @Override
    public void onToolCallStarted(String toolName) {
        out.println();
        out.print("[tool: " + toolName + "] ");
        out.flush();
    }

    @Override
    public void onToolResult(String toolName, boolean success) {
        out.println(success ? "[success]" : "[failed]");
        out.flush();
    }
}
