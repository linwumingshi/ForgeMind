package com.forgemind.llm.openai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible SSE 解析器（JDK 实现，无第三方依赖）。
 *
 * <p>职责：SSE 字节/文本 → SSE 事件 → data 载荷文本。不解析 JSON、
 * 不执行 Tool、不触碰 Context/AgentLoop/Retry/Permission。</p>
 *
 * <p>支持：{@code data: {...}}、{@code data: [DONE]}、LF/CRLF、空行事件边界、
 * UTF-8（含 BOM）、多行 data（同事件按 {@code \n} 拼接）、空 data 忽略、
 * malformed 行容错（非 data 字段忽略）、EOF 正常结束、IO 中断回调
 * {@link Listener#onError}。</p>
 */
public final class OpenAiSseParser {

    /** OpenAI 流结束标记。 */
    public static final String DONE = "[DONE]";

    /** 事件回调。 */
    public interface Listener {

        /**
         * 收到一个事件的 data 载荷（多行 data 已拼接；不含 "data: " 前缀）。
         * {@code [DONE]} 不回调本方法。
         */
        void onData(String data);

        /** 流正常结束（EOF 或 [DONE]）。 */
        void onComplete();

        /** 流被中断（连接关闭 / IO 错误）。 */
        void onError(IOException error);
    }

    private OpenAiSseParser() {
    }

    /**
     * 解析 SSE 流（阻塞直到 EOF / [DONE] / IO 中断）。
     *
     * @param input    SSE 字节流
     * @param listener 事件回调（非 null）
     */
    public static void parse(InputStream input, Listener listener) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        List<String> pendingData = new ArrayList<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1); // 剥离 UTF-8 BOM（仅首行可能）
                }
                if (line.isEmpty()) {
                    flushEvent(pendingData, listener);
                    continue;
                }
                if (line.startsWith("data:")) {
                    String payload = line.substring("data:".length());
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    if (payload.isEmpty()) {
                        continue; // 空 data 忽略
                    }
                    if (DONE.equals(payload)) {
                        flushEvent(pendingData, listener);
                        listener.onComplete();
                        return;
                    }
                    pendingData.add(payload);
                }
                // 其他字段（event: / id: / : comment 等）忽略（容错）
            }
            // EOF：冲刷最后事件并正常结束
            flushEvent(pendingData, listener);
            listener.onComplete();
        } catch (IOException e) {
            listener.onError(e);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    private static void flushEvent(List<String> pendingData, Listener listener) {
        if (pendingData.isEmpty()) {
            return;
        }
        listener.onData(String.join("\n", pendingData));
        pendingData.clear();
    }
}
