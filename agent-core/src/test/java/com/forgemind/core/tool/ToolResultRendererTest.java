package com.forgemind.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ToolResult;
import org.junit.jupiter.api.Test;

class ToolResultRendererTest {

    @Test
    void rendersSuccessfulOutputWithMetadata() {
        String text = ToolResultRenderer.render(ToolResult.success("hello"), "read_file", 1000);
        assertTrue(text.contains("[tool: read_file]"));
        assertTrue(text.contains("[success: true]"));
        assertTrue(text.contains("[exitCode: null]"));
        assertTrue(text.contains("[truncated: false]"));
        assertTrue(text.contains("hello"));
    }

    @Test
    void rendersFailureWithError() {
        String text = ToolResultRenderer.render(ToolResult.failure("boom"), "shell", 1000);
        assertTrue(text.contains("[success: false]"));
        assertTrue(text.contains("ERROR: boom"));
    }

    @Test
    void emptyOutputShowsNoOutputPlaceholder() {
        String text = ToolResultRenderer.render(ToolResult.success(null), "list_files", 1000);
        assertTrue(text.contains("(no output)"));
    }

    @Test
    void shortOutputNotTruncated() {
        String text = ToolResultRenderer.render(ToolResult.success("short"), "read_file", 1000);
        assertTrue(text.contains("[truncated: false]"));
        assertTrue(text.contains("short"));
    }

    @Test
    void longOutputTruncatedAtContextLimit() {
        String big = "x".repeat(2000);
        String text = ToolResultRenderer.render(ToolResult.success(big), "read_file", 100);
        assertTrue(text.contains("[truncated: true]"));
        assertTrue(text.contains("[output truncated: context output limit]"));
        // 截断标记位置 = 元数据前缀 + limit + 换行
        String prefix = "[tool: read_file]\n[success: true]\n[exitCode: null]\n[truncated: true]\n";
        assertEquals(prefix.length() + 100 + 1, text.indexOf("[output truncated"));
    }

    @Test
    void toolAlreadyTruncatedIsNotTruncatedAgain() {
        String partial = "partial output";
        ToolResult result = new ToolResult(null, true, partial, null, 0, true);
        String text = ToolResultRenderer.render(result, "shell", 10);
        // 已 truncated=true：即使超过 limit 也不二次截断，输出完整
        assertTrue(text.contains("[truncated: true]"));
        assertTrue(text.contains(partial));
        assertFalse(text.contains("[output truncated: context output limit]"));
    }

    @Test
    void unicodeContentSurvivesTruncation() {
        String chinese = "你好世界";
        String text = ToolResultRenderer.render(ToolResult.success(chinese), "search", 100);
        assertTrue(text.contains("你好世界"));
        // 中文按字符计数：limit=2 截断后保留前 2 个字符
        String truncated = ToolResultRenderer.render(ToolResult.success(chinese), "search", 2);
        assertTrue(truncated.contains("你好"));
        assertTrue(truncated.contains("[output truncated: context output limit]"));
    }

    @Test
    void originalToolResultRemainsUntouched() {
        ToolResult original = ToolResult.success("original content");
        ToolResultRenderer.render(original, "read_file", 5);
        assertEquals("original content", original.output());
        assertFalse(original.truncated());
    }
}
