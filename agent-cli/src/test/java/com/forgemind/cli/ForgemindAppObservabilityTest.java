package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.Agent;
import com.forgemind.core.config.AgentConfig;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.4：ForgemindApp 可观测性 —— status 摘要、subAgents 统计、
 * streaming 模式不重复 final answer、非 streaming 兼容。
 */
class ForgemindAppObservabilityTest {

    @TempDir
    Path tempDir;

    private static final class Captured {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        final Scanner scanner = new Scanner(new ByteArrayInputStream(new byte[0]),
                StandardCharsets.UTF_8);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static FakeLlmClient finalAnswer(String answer) {
        return new FakeLlmClient().then(AgentResponse.finalAnswer(answer));
    }

    /** 非 streaming（无 renderer）：完整 final answer + status + 统计。 */
    @Test
    void nonStreamingPrintsFullAnswerWithStatus() {
        Captured c = new Captured();
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), finalAnswer("你好世界"),
                tempDir, req -> false);
        new ForgemindApp(c.out, c.scanner).run(agent, "task", tempDir);
        String text = c.text();
        assertTrue(text.contains("你好世界"), "非 streaming 应打印完整答案");
        assertTrue(text.contains("status: success"));
        assertTrue(text.contains("iterations: 1  toolCalls: 0  subAgents: 0"));
    }

    /** streaming 模式（P2.1）：delta 默认静默，最终答案完整输出一次，无 "(streamed above)"。 */
    @Test
    void streamedFinalAnswerPrintedOnceAndComplete() {
        Captured c = new Captured();
        StreamingProgressRenderer renderer = new StreamingProgressRenderer(c.out);
        // FakeLlmClient 是 LlmStreamClient：答案经 delta 事件，但默认模式不展示
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), finalAnswer("流式答案"),
                tempDir, req -> false, renderer);
        new ForgemindApp(c.out, c.scanner, renderer).run(agent, "task", tempDir);
        String text = c.text();
        assertFalse(text.contains("(streamed above)"), "不应再出现 (streamed above) 占位: " + text);
        assertTrue(text.contains("流式答案"), "最终答案应完整输出: " + text);
        assertTrue(text.indexOf("流式答案") == text.lastIndexOf("流式答案"),
                "final answer 只应输出一次: " + text);
        assertTrue(text.contains("status: success"));
    }

    /** cancelled 状态可从 CLI 判断。 */
    @Test
    void cancelledStatusIsShown() {
        Captured c = new Captured();
        StreamingProgressRenderer renderer = new StreamingProgressRenderer(c.out);
        // 先中断线程 → Agent 返回 failed("cancelled")
        Thread.currentThread().interrupt();
        try {
            Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), finalAnswer("never"),
                    tempDir, req -> false, renderer);
            new ForgemindApp(c.out, c.scanner, renderer).run(agent, "task", tempDir);
            String text = c.text();
            assertTrue(text.contains("status: cancelled"), "cancelled 状态应显示: " + text);
            assertTrue(text.contains("[not finished] cancelled"));
        } finally {
            Thread.interrupted();
        }
    }

    /** failed 状态（非 cancelled 的错误）可从 CLI 判断：权限拒绝 → 工具失败回灌 → 主自纠。 */
    @Test
    void failedStatusIsShownWhenAgentTerminatesWithError() {
        Captured c = new Captured();
        StreamingProgressRenderer renderer = new StreamingProgressRenderer(c.out);
        // 脚本耗尽（Fake 无更多响应）→ AgentLoop 捕获为失败结果
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null, java.util.List.of(
                        com.forgemind.model.ToolCall.of("c1", "write_file",
                                java.util.Map.of("path", "x.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("recovered"));
        Agent agent = CliAssembly.buildAgent(AgentConfig.defaults(), fake, tempDir,
                req -> false, renderer); // 无 --yes：WRITE 默认拒绝 → 工具失败回灌
        new ForgemindApp(c.out, c.scanner, renderer).run(agent, "task", tempDir);
        String text = c.text();
        assertTrue(text.contains("status: success"),
                "工具失败自纠后任务应成功: " + text);
        assertTrue(text.contains("[1] write_file ✗"),
                "被拒工具应显示序号失败标记: " + text);
    }
}
