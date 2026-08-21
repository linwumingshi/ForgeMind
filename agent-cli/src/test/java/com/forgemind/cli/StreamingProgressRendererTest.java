package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * P2.1：StreamingProgressRenderer 终端渲染。
 *
 * <p>默认模式（verbose=false）：text delta 不输出（中间 assistant 文本静默，
 * 最终答案由 ForgemindApp 统一输出）；Tool 按序号展示成功/失败（失败含
 * exitCode 与一行 stderr 摘要）；verbose=true 恢复 text delta 实时输出。</p>
 */
class StreamingProgressRendererTest {

    private static final class Captured {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        final StreamingProgressRenderer renderer = new StreamingProgressRenderer(out);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    // ---------- P2.1：默认模式 text delta 静默 / verbose 展示 ----------

    @Test
    void defaultModeIgnoresTextDeltas() {
        Captured c = new Captured();
        c.renderer.onTextDelta("I'll inspect the file first.");
        c.renderer.onTextDelta("Let me check...");
        assertEquals("", c.text(), "默认模式 onTextDelta 不应产生 CLI 输出");
        // 不打印也不 flush，但内部仍标记已流式（供兼容查询）
        assertTrue(c.renderer.hasStreamedText());
    }

    @Test
    void verboseModeBuffersDeltaUntilToolStarts() {
        // P2.4：verbose 下 delta 缓冲，在工具开始时输出（避免最终答案 delta 与 Final block 重复）
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onTextDelta("I'll inspect");
        c.renderer.onTextDelta(" the file.");
        // delta 已收到但尚未触发任何事件 → 不输出（缓冲中）
        assertEquals("", c.text(), "verbose delta 应先缓冲");
        c.renderer.onToolCallStarted("read_file");
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("I'll inspect the file.\n[1] read_file "),
                "工具开始时应先输出缓冲的中间文本: " + text);
    }

    @Test
    void verboseModeFinalDeltaDiscardedByFinishRun() {
        // 最终答案轮的 delta 不输出（finishRun 丢弃），由 ForgemindApp 统一输出一次
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onTextDelta("this is the final answer");
        c.renderer.finishRun();
        assertEquals("", c.text(), "最终答案 delta 应在 finishRun 时丢弃");
        // finishRun 后新一轮事件不携带旧缓冲
        c.renderer.onToolCallStarted("read_file");
        assertEquals("[1] read_file ", c.text().replace("\r\n", "\n"));
    }

    @Test
    void verboseIsOffByDefault() {
        Captured c = new Captured();
        c.renderer.onTextDelta("x");
        assertEquals("", c.text(), "默认 verbose=false");
    }

    // ---------- P2.1：Tool 序号 + 成功/失败 ----------

    @Test
    void printsToolSuccessWithSequence() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("list_files");
        c.renderer.onToolResult("list_files", ToolResult.success("ok"));
        assertEquals("[1] list_files ✓\n", c.text().replace("\r\n", "\n"));
    }

    @Test
    void printsToolFailureWithExitCode() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("shell");
        c.renderer.onToolResult("shell", ToolResult.failure("exit code: 1").withExitCode(1));
        assertEquals("[1] shell ✗ exit=1\n", c.text().replace("\r\n", "\n"));
    }

    @Test
    void printsToolFailureWithoutExitCode() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", ToolResult.failure("no such file"));
        assertEquals("[1] read_file ✗\n", c.text().replace("\r\n", "\n"));
    }

    @Test
    void printsStderrSummaryOnFailure() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("shell");
        ToolResult result = new ToolResult(null, false,
                "line one\n[stderr]\n'javac' is not recognized as an internal or external command\n",
                "exit code: 1", 1, false);
        c.renderer.onToolResult("shell", result);
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("[1] shell ✗ exit=1\n"), "应含失败行: " + text);
        assertTrue(text.contains("  stderr: 'javac' is not recognized as an internal or external command"),
                "应含 stderr 首行摘要: " + text);
    }

    @Test
    void noStderrSummaryWhenOutputHasNoStderrSection() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("shell");
        c.renderer.onToolResult("shell", ToolResult.failure("permission denied").withExitCode(1));
        String text = c.text().replace("\r\n", "\n");
        assertEquals("[1] shell ✗ exit=1\n", text, "无 stderr 分节时不应输出摘要行");
    }

    // ---------- P2.4：verbose 模式展示完整 tool output ----------

    @Test
    void defaultModeDoesNotShowToolOutput() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", ToolResult.success("hello content"));
        String text = c.text().replace("\r\n", "\n");
        assertEquals("[1] read_file ✓\n", text, "默认模式不应展示 tool output");
    }

    @Test
    void verboseModeShowsToolOutputIndented() {
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", ToolResult.success("line one\nline two"));
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("[1] read_file ✓\n"), "事件行不变: " + text);
        assertTrue(text.contains("    line one\n    line two\n"), "tool output 应缩进展示: " + text);
    }

    @Test
    void verboseModeShowsFailedToolOutputWithStderrSection() {
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onToolCallStarted("shell");
        ToolResult result = new ToolResult(null, false,
                "stdout line\n[stderr]\nfailed command\n", "exit code: 1", 1, false);
        c.renderer.onToolResult("shell", result);
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("[1] shell ✗ exit=1\n"), "失败行不变: " + text);
        assertTrue(text.contains("  stderr: failed command"), "stderr 摘要仍显示: " + text);
        assertTrue(text.contains("    stdout line\n    [stderr]\n    failed command"),
                "verbose 应缩进展示完整 output: " + text);
    }

    @Test
    void verboseToolOutputTruncatedByLineCount() {
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onToolCallStarted("shell");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            big.append("line").append(i).append('\n');
        }
        c.renderer.onToolResult("shell", ToolResult.success(big.toString()));
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("[output truncated: 180 more lines]"),
                "超行数应提示截断: " + text);
        assertTrue(text.contains("    line0\n"), "应展示首行");
        assertFalse(text.contains("    line299\n"), "不应展示超限行");
    }

    @Test
    void verboseToolOutputTruncatedByCharCount() {
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onToolCallStarted("shell");
        // 单行超 8KB：应触发字符截断
        String huge = "x".repeat(10_000);
        c.renderer.onToolResult("shell", ToolResult.success(huge));
        String text = c.text().replace("\r\n", "\n");
        assertTrue(text.contains("[output truncated]\n"), "超字符应提示截断: " + text);
        assertFalse(text.contains("x".repeat(10_000)), "不得输出完整超长内容");
    }

    @Test
    void verboseNoOutputProducesNothingExtra() {
        Captured c = new Captured();
        c.renderer.setVerbose(true);
        c.renderer.onToolCallStarted("list_files");
        c.renderer.onToolResult("list_files", ToolResult.success(null));
        assertEquals("[1] list_files ✓\n", c.text().replace("\r\n", "\n"),
                "output 为 null 时 verbose 不追加内容");
    }

    @Test
    void stderrSummaryIsTruncatedWhenTooLong() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("shell");
        String longStderr = "e".repeat(500);
        ToolResult result = new ToolResult(null, false,
                "[stderr]\n" + longStderr, "exit code: 2", 2, false);
        c.renderer.onToolResult("shell", result);
        String text = c.text().replace("\r\n", "\n");
        String summary = text.lines().filter(l -> l.startsWith("  stderr: ")).findFirst().orElseThrow();
        // 摘要行 = "  stderr: " + 截断后内容（≤120 + "..."）
        assertTrue(summary.length() <= "  stderr: ".length() + 120 + 3,
                "stderr 摘要应截断到约 120 字符: " + summary.length());
        assertTrue(summary.endsWith("..."), "超长应追加省略号: " + summary);
        assertFalse(text.contains(longStderr), "不得输出完整超长 stderr");
    }

    @Test
    void toolSequencesIncrementAcrossCalls() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("list_files");
        c.renderer.onToolResult("list_files", ToolResult.success("a"));
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", ToolResult.success("b"));
        c.renderer.onToolCallStarted("shell");
        c.renderer.onToolResult("shell", ToolResult.failure("boom").withExitCode(1));
        assertEquals("[1] list_files ✓\n[2] read_file ✓\n[3] shell ✗ exit=1\n",
                c.text().replace("\r\n", "\n"));
    }

    @Test
    void resetRestartsSequenceFromOne() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", ToolResult.success("x"));
        c.renderer.reset();
        c.renderer.onToolCallStarted("shell");
        c.renderer.onToolResult("shell", ToolResult.success("y"));
        assertEquals("[1] read_file ✓\n[1] shell ✓\n",
                c.text().replace("\r\n", "\n"), "reset 后序号应重新从 [1] 开始");
    }

    // ---------- 兼容：旧 2 参 onToolResult 仍可用 ----------

    @Test
    void legacyTwoArgOnToolResultStillWorks() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("write_file");
        c.renderer.onToolResult("write_file", true);
        c.renderer.onToolCallStarted("shell");
        c.renderer.onToolResult("shell", false);
        assertEquals("[1] write_file ✓\n[2] shell ✗\n",
                c.text().replace("\r\n", "\n"));
    }

    // ---------- SubAgent 生命周期（保持不变） ----------

    @Test
    void printsSubAgentLifecycle() {
        Captured c = new Captured();
        c.renderer.onSubAgentStarted("analyze module A");
        c.renderer.onSubAgentResult("analyze module A", true);
        c.renderer.onSubAgentStarted("implement fix");
        c.renderer.onSubAgentResult("implement fix", false);
        String text = c.text();
        assertTrue(text.contains("[subagent:start] analyze module A [complete]"));
        assertTrue(text.contains("[subagent:start] implement fix [failed]"));
    }

    @Test
    void subAgentLifecycleWithChineseTask() {
        Captured c = new Captured();
        c.renderer.onSubAgentStarted("分析模块");
        c.renderer.onSubAgentResult("分析模块", true);
        String text = c.text();
        assertTrue(text.contains("[subagent:start] 分析模块 [complete]"),
                "中文任务应原样输出，无乱码: " + text);
    }

    @Test
    void longSubAgentTaskIsTruncated() {
        Captured c = new Captured();
        String longTask = "t".repeat(200);
        c.renderer.onSubAgentStarted(longTask);
        c.renderer.onSubAgentResult(longTask, true);
        String text = c.text();
        assertTrue(text.contains("..."), "超长任务应截断");
        assertFalse(text.contains("t".repeat(200)), "不应输出完整超长任务");
    }

    @Test
    void tracksSubAgentCountAndStreamedFlag() {
        Captured c = new Captured();
        assertEquals(0, c.renderer.subAgentCount());
        assertFalse(c.renderer.hasStreamedText());
        c.renderer.onSubAgentStarted("a");
        c.renderer.onSubAgentStarted("b");
        assertEquals(2, c.renderer.subAgentCount());
        // SubAgent 事件本身不是 text delta
        assertFalse(c.renderer.hasStreamedText());
        c.renderer.onTextDelta("x");
        assertTrue(c.renderer.hasStreamedText());
    }

    @Test
    void rendererHasNoDecisionSideEffects() {
        // 渲染器不实现任何"执行"逻辑：事件驱动，仅输出；未触发的回调不产生输出
        Captured c = new Captured();
        assertEquals("", c.text());
        c.renderer.onTextDelta("a"); // 默认模式静默
        assertEquals("", c.text());
    }
}
