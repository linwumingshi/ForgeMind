package com.forgemind.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.forgemind.model.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M9.1：ProgressListener 新增 default 方法不破坏既有实现与 NOOP。
 * P2.1：新增 {@link ProgressListener#onToolResult(String, ToolResult)} 载荷重载，
 * default 实现委托旧 2 参方法 —— 仅覆写旧方法的实现无需改动仍能收到事件。
 */
class ProgressListenerDefaultsTest {

    @Test
    void noopIsSafeForNewCallbacks() {
        ProgressListener.NOOP.onSubAgentStarted("task");
        ProgressListener.NOOP.onSubAgentResult("task", true);
        ProgressListener.NOOP.onSubAgentResult("task", false);
        ProgressListener.NOOP.onToolResult("shell", ToolResult.failure("boom").withExitCode(1));
        // 无异常即通过
    }

    @Test
    void existingImplementationsRemainValid() {
        // M8 既有的匿名实现只覆写旧方法，新方法走 default no-op
        ProgressListener legacy = new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
            }
        };
        legacy.onTextDelta("x");
        legacy.onSubAgentStarted("sub");
        legacy.onSubAgentResult("sub", true);
        legacy.onToolResult("shell", ToolResult.failure("boom"));
    }

    @Test
    void newCallbacksAreObservable() {
        List<String> started = new ArrayList<>();
        List<String[]> results = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onSubAgentStarted(String task) {
                started.add(task);
            }

            @Override
            public void onSubAgentResult(String task, boolean success) {
                results.add(new String[]{task, String.valueOf(success)});
            }
        };
        listener.onSubAgentStarted("analyze");
        listener.onSubAgentResult("analyze", true);
        listener.onSubAgentResult("analyze", false);
        assertEquals(List.of("analyze"), started);
        assertEquals(2, results.size());
        assertEquals("true", results.get(0)[1]);
        assertEquals("false", results.get(1)[1]);
    }

    @Test
    void toolResultOverloadDelegatesToTwoArgByDefault() {
        // 只覆写旧 2 参方法的实现：调用新 3 参载荷重载时，default 应委托到 2 参
        List<String> seen = new ArrayList<>();
        ProgressListener legacy = new ProgressListener() {
            @Override
            public void onToolResult(String toolName, boolean success) {
                seen.add(toolName + "=" + success);
            }
        };
        legacy.onToolResult("read_file", ToolResult.success("data"));
        legacy.onToolResult("shell", ToolResult.failure("boom").withExitCode(1));
        assertEquals(List.of("read_file=true", "shell=false"), seen,
                "3 参 default 应委托旧 2 参方法（success 取自 ToolResult）");
    }

    @Test
    void toolResultOverloadCanBeOverriddenDirectly() {
        // 实现可覆写 3 参载荷重载拿到完整 ToolResult（exitCode 等）
        List<String> exitCodes = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onToolResult(String toolName, ToolResult result) {
                exitCodes.add(result.exitCode() == null ? "none" : String.valueOf(result.exitCode()));
            }
        };
        listener.onToolResult("shell", ToolResult.failure("boom").withExitCode(7));
        assertEquals(List.of("7"), exitCodes);
    }

    @Test
    void noopIsSingletonConstant() {
        assertSame(ProgressListener.NOOP, ProgressListener.NOOP);
    }
}
