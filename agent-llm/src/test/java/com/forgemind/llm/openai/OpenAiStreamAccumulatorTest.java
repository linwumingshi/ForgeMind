package com.forgemind.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.AgentResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiStreamAccumulatorTest {

    private static String chunk(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String chunk(String content, String finishReason) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    @Test
    void singleTextDelta() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("hello"));
        AgentResponse response = acc.finish();
        assertFalse(response.hasToolCalls());
        assertEquals("hello", response.content());
    }

    @Test
    void multipleDeltasAreConcatenated() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("a"));
        acc.accept(chunk("b"));
        acc.accept(chunk("c"));
        assertEquals("abc", acc.finish().content());
    }

    @Test
    void chineseDeltasAreConcatenated() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("你"));
        acc.accept(chunk("好"));
        acc.accept(chunk("世界"));
        assertEquals("你好世界", acc.finish().content());
    }

    @Test
    void nullContentDeltaIsIgnored() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":null}]}");
        acc.accept(chunk("x"));
        assertEquals("x", acc.finish().content());
    }

    @Test
    void emptyDeltaChunkIsIgnored() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{}}]}");
        acc.accept(chunk("ok"));
        assertEquals("ok", acc.finish().content());
    }

    @Test
    void textAndToolCallMixed() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("我先读取文件"));
        acc.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        AgentResponse response = acc.finish();
        assertEquals("我先读取文件", response.content(), "tool_call 不应丢失已有 text");
        assertTrue(response.hasToolCalls());
        assertEquals("c0", response.toolCalls().get(0).id());
        assertEquals(Map.of("path", "a.txt"), response.toolCalls().get(0).arguments());
        assertEquals("tool_calls", response.finishReason());
    }

    @Test
    void toolCallArgumentsStreamedAcrossChunks() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]},\"finish_reason\":null}]}");
        acc.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"th\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
        AgentResponse response = acc.finish();
        assertTrue(response.hasToolCalls());
        assertEquals(Map.of("path", "a.txt"), response.toolCalls().get(0).arguments());
    }

    @Test
    void multipleToolCallsStreamed() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"id\":\"c1\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        AgentResponse response = acc.finish();
        assertEquals(2, response.toolCalls().size());
        assertEquals("c0", response.toolCalls().get(0).id());
        assertEquals("c1", response.toolCalls().get(1).id());
    }

    @Test
    void finishReasonStop() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("answer", "stop"));
        assertEquals("stop", acc.finish().finishReason());
    }

    @Test
    void finishReasonLength() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("partial", "length"));
        assertEquals("length", acc.finish().finishReason());
    }

    @Test
    void usageRecordedFromFinalChunk() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("done", "stop"));
        acc.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");
        assertTrue(acc.hasUsage());
        assertEquals(10, acc.promptTokens());
        assertEquals(5, acc.completionTokens());
        assertEquals(15, acc.totalTokens());
        assertEquals("done", acc.finish().content());
    }

    @Test
    void missingUsageIsNotFabricated() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept(chunk("done", "stop"));
        assertFalse(acc.hasUsage());
        assertEquals(0, acc.totalTokens());
    }

    @Test
    void malformedChunkIsIgnored() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("not-json");
        acc.accept("{broken");
        assertTrue(acc.isEmpty());
        acc.accept(chunk("ok"));
        assertEquals("ok", acc.finish().content());
    }

    @Test
    void missingChoicesChunkOnlyForUsage() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[]}");
        assertTrue(acc.isEmpty());
        acc.accept(chunk("x"));
        assertEquals("x", acc.finish().content());
    }

    @Test
    void missingDeltaIsSafe() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{}]}");
        assertTrue(acc.isEmpty());
    }

    @Test
    void missingToolCallsNodeIsSafe() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}");
        AgentResponse response = acc.finish();
        assertFalse(response.hasToolCalls());
        assertNull(response.finishReason());
    }

    @Test
    void missingToolCallIndexAssignedInOrder() {
        // OpenAI 若缺 index：OpenAiStreamAccumulator 按出现顺序分配
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        acc.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c1\","
                + "\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"c2\",\"function\":{\"name\":\"shell\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        AgentResponse response = acc.finish();
        assertEquals(2, response.toolCalls().size());
        assertEquals("c1", response.toolCalls().get(0).id());
        assertEquals("c2", response.toolCalls().get(1).id());
    }

    @Test
    void emptyAccumulatorFinish() {
        OpenAiStreamAccumulator acc = new OpenAiStreamAccumulator();
        AgentResponse response = acc.finish();
        assertEquals("", response.content());
        assertFalse(response.hasToolCalls());
        assertNull(response.finishReason());
    }
}
