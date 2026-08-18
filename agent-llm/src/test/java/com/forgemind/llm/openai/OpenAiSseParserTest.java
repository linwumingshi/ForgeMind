package com.forgemind.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiSseParserTest {

    /** 记录回调的测试监听器。 */
    private static final class RecordingListener implements OpenAiSseParser.Listener {
        final List<String> data = new ArrayList<>();
        boolean completed;
        IOException error;

        @Override
        public void onData(String data) {
            this.data.add(data);
        }

        @Override
        public void onComplete() {
            this.completed = true;
        }

        @Override
        public void onError(IOException error) {
            this.error = error;
        }
    }

    private RecordingListener parse(String sse) {
        RecordingListener listener = new RecordingListener();
        OpenAiSseParser.parse(bytes(sse), listener);
        return listener;
    }

    private static InputStream bytes(String sse) {
        return new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
    }

    private static final String CHUNK_JSON =
            "{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}";

    @Test
    void singleTextChunk() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\n\n");
        assertEquals(1, l.data.size());
        assertEquals(CHUNK_JSON, l.data.get(0));
        assertTrue(l.completed);
        assertEquals(null, l.error);
    }

    @Test
    void multipleTextChunks() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n";
        RecordingListener l = parse(sse);
        assertEquals(2, l.data.size());
        assertTrue(l.data.get(0).contains("\"content\":\"a\""));
        assertTrue(l.data.get(1).contains("\"content\":\"b\""));
        assertTrue(l.completed);
    }

    @Test
    void chineseUtf8() {
        RecordingListener l = parse("data: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}\n\n");
        assertEquals(1, l.data.size());
        assertTrue(l.data.get(0).contains("你好世界"));
    }

    @Test
    void crlfLineEndings() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\r\n\r\n");
        assertEquals(1, l.data.size());
        assertTrue(l.completed);
    }

    @Test
    void lfLineEndings() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\n\n");
        assertEquals(1, l.data.size());
    }

    @Test
    void doneMarkerCompletesWithoutDataCallback() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\n\ndata: [DONE]\n\n");
        assertEquals(1, l.data.size(), "[DONE] 不应作为 data 回调");
        assertTrue(l.completed);
    }

    @Test
    void emptyDeltaIsIgnored() {
        // data: 空载荷（无内容）→ 忽略
        RecordingListener l = parse("data:\n\ndata: " + CHUNK_JSON + "\n\n");
        assertEquals(1, l.data.size());
    }

    @Test
    void malformedSseLinesAreIgnored() {
        String sse = "event: message\n"          // 非 data 字段 → 忽略
                + ": comment line\n"
                + "id: 1\n"
                + "data: " + CHUNK_JSON + "\n\n";
        RecordingListener l = parse(sse);
        assertEquals(1, l.data.size());
        assertEquals(CHUNK_JSON, l.data.get(0));
    }

    @Test
    void multipleDataLinesInOneEventAreJoined() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n";
        RecordingListener l = parse(sse);
        assertEquals(1, l.data.size(), "同一事件多行 data 应合并为一次回调");
        assertTrue(l.data.get(0).contains("\"content\":\"a\""));
        assertTrue(l.data.get(0).contains("\"content\":\"b\""));
    }

    @Test
    void bomIsStripped() {
        String sse = "\uFEFFdata: " + CHUNK_JSON + "\n\n";
        RecordingListener l = parse(sse);
        assertEquals(1, l.data.size());
        assertEquals(CHUNK_JSON, l.data.get(0));
    }

    @Test
    void eofWithoutDoneCompletesAndFlushesLastEvent() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\n\n"); // 无 [DONE]，末尾空行
        assertTrue(l.completed);
        assertEquals(1, l.data.size());
    }

    @Test
    void eofFlushesEventWithoutTrailingBlankLine() {
        RecordingListener l = parse("data: " + CHUNK_JSON + "\n"); // 无结尾空行
        assertTrue(l.completed);
        assertEquals(1, l.data.size(), "EOF 时应冲刷最后一个未结束事件");
    }

    @Test
    void malformedJsonPayloadIsPassedThrough() {
        // Parser 不解析 JSON：malformed 载荷原样回调，不崩溃
        RecordingListener l = parse("data: {not-json\n\n");
        assertEquals(1, l.data.size());
        assertEquals("{not-json", l.data.get(0));
        assertTrue(l.completed);
        assertEquals(null, l.error);
    }

    @Test
    void connectionInterruptionRaisesError() {
        // 模拟中途关闭流（连接重置）
        InputStream input = new InputStream() {
            private int count = 0;

            @Override
            public int read() throws IOException {
                if (count++ < 10) {
                    return 'd';
                }
                throw new IOException("connection reset");
            }
        };
        RecordingListener listener = new RecordingListener();
        OpenAiSseParser.parse(input, listener);
        assertFalse(listener.completed, "中断不应触发 onComplete");
        assertTrue(listener.error != null, "中断应回调 onError");
        assertTrue(listener.error.getMessage().contains("connection reset"));
    }
}
