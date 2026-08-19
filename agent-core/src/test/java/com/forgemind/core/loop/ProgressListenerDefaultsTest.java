package com.forgemind.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M9.1：ProgressListener 新增 default 方法不破坏既有实现与 NOOP。
 */
class ProgressListenerDefaultsTest {

    @Test
    void noopIsSafeForNewCallbacks() {
        ProgressListener.NOOP.onSubAgentStarted("task");
        ProgressListener.NOOP.onSubAgentResult("task", true);
        ProgressListener.NOOP.onSubAgentResult("task", false);
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
    void noopIsSingletonConstant() {
        assertSame(ProgressListener.NOOP, ProgressListener.NOOP);
    }
}
