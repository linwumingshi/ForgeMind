package com.forgemind.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamToolCallAccumulatorTest {

    @Test
    void singleCompleteToolCall() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "call_abc", "read_file", "{\"path\":\"a.txt\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals(1, calls.size());
        assertEquals("call_abc", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
        assertEquals(Map.of("path", "a.txt"), calls.get(0).arguments());
    }

    @Test
    void argumentsSplitAcrossChunksIsParsedOnce() {
        // 关键回归：绝不能对 "{\"pa" 单独解析失败
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "call_x", "read_file", "{\"pa");
        acc.onDelta(0, null, null, "th\":\"a.txt\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals(1, calls.size());
        assertEquals(Map.of("path", "a.txt"), calls.get(0).arguments());
    }

    @Test
    void idAndNameSplitAcrossChunks() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "call_", "read_", null);
        acc.onDelta(0, "abc", "file", "{\"pa");
        acc.onDelta(0, null, null, "th\":\"a.txt\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals("call_abc", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
        assertEquals(Map.of("path", "a.txt"), calls.get(0).arguments());
    }

    @Test
    void multipleToolCallsInterleaved() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "c0", "read_file", "{\"path\":\"a");
        acc.onDelta(1, "c1", "search", "{\"query\":\"foo");
        acc.onDelta(0, null, null, ".txt\"}");
        acc.onDelta(1, null, null, "bar\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals(2, calls.size());
        assertEquals("c0", calls.get(0).id());
        assertEquals(Map.of("path", "a.txt"), calls.get(0).arguments());
        assertEquals("c1", calls.get(1).id());
        assertEquals(Map.of("query", "foobar"), calls.get(1).arguments());
    }

    @Test
    void finishOrderFollowsIndex() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(1, "c1", "search", "{}");
        acc.onDelta(0, "c0", "read_file", "{}");
        List<ToolCall> calls = acc.finish();
        assertEquals("c0", calls.get(0).id());
        assertEquals("c1", calls.get(1).id());
    }

    @Test
    void emptyDeltaIsIgnored() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, null, null, null);
        acc.onDelta(0, "", "", "");
        assertTrue(acc.isEmpty());
        assertTrue(acc.finish().isEmpty());
    }

    @Test
    void missingIdAndNameProduceEmptyStrings() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, null, null, "{\"path\":\"a.txt\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals(1, calls.size());
        assertEquals("", calls.get(0).id());
        assertEquals("", calls.get(0).name());
    }

    @Test
    void malformedArgumentsFallsBackToEmptyMap() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "c0", "read_file", "not-json");
        List<ToolCall> calls = acc.finish();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).arguments().isEmpty());
    }

    @Test
    void argumentsWithChineseValues() {
        StreamToolCallAccumulator acc = new StreamToolCallAccumulator();
        acc.onDelta(0, "c0", "write_file", "{\"content\":\"你");
        acc.onDelta(0, null, null, "好\"}");
        List<ToolCall> calls = acc.finish();
        assertEquals(Map.of("content", "你好"), calls.get(0).arguments());
    }
}
