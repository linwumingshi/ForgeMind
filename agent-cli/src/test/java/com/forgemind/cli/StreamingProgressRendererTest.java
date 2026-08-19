package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * M8.5：StreamingProgressRenderer 终端渲染。文本增量实时输出（flush）、
 * Tool 调用/结果展示；纯观察层，无任何决策副作用。
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

    @Test
    void printsTextDeltasIncrementally() {
        Captured c = new Captured();
        c.renderer.onTextDelta("你");
        // 每个 delta 必须立即 flush（增量可见，而非攒到结束）
        assertEquals("你", c.text());
        c.renderer.onTextDelta("好");
        c.renderer.onTextDelta("世界");
        assertEquals("你好世界", c.text());
    }

    @Test
    void printsToolCallAndResult() {
        Captured c = new Captured();
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", true);
        c.renderer.onToolCallStarted("write_file");
        c.renderer.onToolResult("write_file", false);
        String text = c.text();
        assertTrue(text.contains("[tool: read_file] [success]"));
        assertTrue(text.contains("[tool: write_file] [failed]"));
    }

    @Test
    void interleavesDeltasAndToolLifecycle() {
        Captured c = new Captured();
        c.renderer.onTextDelta("thinking");
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", true);
        c.renderer.onTextDelta("done");
        // 归一化平台换行（Windows 上 println 输出 \r\n）
        String text = c.text().replace("\r\n", "\n");
        assertEquals("thinking\n[tool: read_file] [success]\ndone", text);
    }

    @Test
    void rendererHasNoDecisionSideEffects() {
        // 渲染器不实现任何"执行"逻辑：事件驱动，仅输出；未触发的回调不产生输出
        Captured c = new Captured();
        assertEquals("", c.text());
        c.renderer.onTextDelta("a");
        assertFalse(c.text().isEmpty());
    }

    // ---------- M9.4 ----------

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
    void interleavesToolAndSubAgentLifecycle() {
        Captured c = new Captured();
        c.renderer.onTextDelta("planning");
        c.renderer.onSubAgentStarted("sub");
        c.renderer.onSubAgentResult("sub", true);
        c.renderer.onToolCallStarted("read_file");
        c.renderer.onToolResult("read_file", true);
        String text = c.text().replace("\r\n", "\n");
        // 连续事件间不得产生空行；结果以换行结束
        assertEquals("planning\n[subagent:start] sub [complete]\n[tool: read_file] [success]\n",
                text);
    }
}
