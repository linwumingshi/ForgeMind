package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8.5：Cancellation / Interrupt 边界。线程中断 → AgentLoop 返回
 * failed("cancelled")；运行中的 Tool 不被 AgentLoop 主动终止（自然完成或
 * 由 Tool 自身语义结束），循环在下一轮边界停止。
 */
class AgentLoopCancellationTest {

    @TempDir
    Path workspace;

    /** 中断发生在任何迭代前：第一轮边界即返回 failed("cancelled")，零迭代零工具。 */
    @Test
    void interruptBeforeRunReturnsCancelled() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("unused"));
        Thread.currentThread().interrupt();
        try {
            AgentResult result = AgentHarness.newLoop(workspace, fake,
                    AgentConfig.defaults(), req -> false).run("task");
            assertFalse(result.finished());
            assertTrue(result.error().contains("cancelled"));
            assertEquals(0, result.iterations());
            assertEquals(0, result.toolCallCount());
            assertEquals(0, fake.callCount(), "中断后不应再调用 LLM");
        } finally {
            Thread.interrupted(); // 清理中断标志，避免污染后续测试线程
        }
    }

    /**
     * 中断发生在 Tool 执行期间（模拟：进度回调触发中断）：Tool 仍完整执行
     * （副作用落盘），循环不中断 Tool，而在下一轮边界以 cancelled 停止。
     */
    @Test
    void interruptDuringToolStillExecutesToolThenCancels() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "write_file",
                                Map.of("path", "created.txt", "content", "x")))))
                .then(AgentResponse.finalAnswer("never reached"));
        AgentLoop loop = AgentHarness.newLoop(workspace, fake, AgentConfig.defaults(), req -> true,
                new ProgressListener() {
                    @Override
                    public void onToolCallStarted(String toolName) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            AgentResult result = loop.run("task");
            assertFalse(result.finished());
            assertTrue(result.error().contains("cancelled"));
            // Tool 未被杀死：write_file 已完整执行，副作用真实落盘
            assertEquals(1, result.toolCallCount());
            assertTrue(Files.exists(workspace.resolve("created.txt")),
                    "Tool 应完整执行（副作用可见），而非被取消中断");
            // 不再发起第二轮 LLM 调用（第一轮执行工具后即停止）
            assertEquals(1, fake.callCount());
        } finally {
            Thread.interrupted();
        }
    }

    /** Streaming 通道下的取消：中断后立即停止，不产生第二轮流式请求。 */
    @Test
    void interruptStopsStreamingLoopAtBoundary() {
        FakeLlmClient fake = new FakeLlmClient()
                .then(AgentResponse.finalAnswer("partial"));
        Thread.currentThread().interrupt();
        try {
            AgentResult result = AgentHarness.newLoop(workspace, fake,
                    AgentConfig.defaults(), req -> false).run("task");
            assertFalse(result.finished());
            assertTrue(result.error().contains("cancelled"));
        } finally {
            Thread.interrupted();
        }
    }
}
