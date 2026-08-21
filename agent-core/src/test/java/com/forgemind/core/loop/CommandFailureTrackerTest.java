package com.forgemind.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandFailureTrackerTest {

    @Test
    void firstFailureCountsOneWithoutHint() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        assertEquals(1, tracker.recordFailure("java demo.OrderDemo"));
        assertNull(CommandFailureTracker.hintFor(1));
    }

    @Test
    void secondConsecutiveFailureGivesWeakHint() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        assertEquals(1, tracker.recordFailure("java demo.OrderDemo"));
        assertEquals(2, tracker.recordFailure("java demo.OrderDemo"));
        String hint = CommandFailureTracker.hintFor(2);
        assertTrue(hint.contains("failed repeatedly"));
        assertFalse(hint.contains("Do not retry it again"));
    }

    @Test
    void thirdConsecutiveFailureGivesStrongHint() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        tracker.recordFailure("java demo.OrderDemo");
        tracker.recordFailure("java demo.OrderDemo");
        assertEquals(3, tracker.recordFailure("java demo.OrderDemo"));
        String hint = CommandFailureTracker.hintFor(3);
        assertTrue(hint.contains("Do not retry it again"));
        // 4 次及以上沿用强提示
        assertTrue(CommandFailureTracker.hintFor(5).contains("Do not retry it again"));
    }

    @Test
    void normalizeMergesEquivalentVariants() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        // 同一命令的等义变体必须共享连续失败计数
        tracker.recordFailure("cmd /c \"Java Demo\" 2>&1");
        int second = tracker.recordFailure("CMD /C java demo");
        assertEquals(2, second, "cmd /c 前缀与 2>&1 尾部应被归一");
        // 三个变体归一同 key：连续失败 3 次
        assertEquals(3, tracker.recordFailure("java demo"));
    }

    @Test
    void differentCommandsDoNotShareCounter() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        tracker.recordFailure("java demo.OrderDemo");
        tracker.recordFailure("java demo.OrderDemo");
        // 新命令从 1 开始计数
        assertEquals(1, tracker.recordFailure("javac -d . demo/OrderDemo.java"));
    }

    @Test
    void successResetsCounter() {
        CommandFailureTracker tracker = new CommandFailureTracker();
        tracker.recordFailure("java demo.OrderDemo");
        tracker.recordFailure("java demo.OrderDemo");
        tracker.recordSuccess("java demo.OrderDemo");
        // 成功后重新失败从 1 开始
        assertEquals(1, tracker.recordFailure("java demo.OrderDemo"));
        assertNull(CommandFailureTracker.hintFor(1));
    }

    // ---------- P2.3：hint 文案增强（计数/归一/阈值行为不变，仅文案含诊断方向） ----------

    @Test
    void weakHintGivesCompileAndInspectionGuidance() {
        String weak = CommandFailureTracker.hintFor(2);
        assertTrue(weak.contains("inspect the previous stderr/output"),
                "weak hint 应引导先看 stderr/output: " + weak);
        assertTrue(weak.contains("compilation succeeded"),
                "weak hint 应提示检查编译是否成功: " + weak);
        assertTrue(weak.contains("package/classpath"),
                "weak hint 应提示检查 package/classpath: " + weak);
    }

    @Test
    void strongHintGivesDiagnosticAlternatives() {
        String strong = CommandFailureTracker.hintFor(3);
        assertTrue(strong.contains("changing the underlying approach"),
                "strong hint 应要求改变方法: " + strong);
        assertTrue(strong.contains("compilation/output results"),
                "strong hint 应提示检查编译/输出结果: " + strong);
        assertTrue(strong.contains("different diagnostic command"),
                "strong hint 应提供其他诊断途径: " + strong);
    }

    @Test
    void countingThresholdsUnchangedByHintEnhancement() {
        // P2.3 只改文案：1 次无提示、2 次 weak、3 次 strong 的阈值行为必须原样
        CommandFailureTracker tracker = new CommandFailureTracker();
        assertEquals(1, tracker.recordFailure("cmd /c \"Java Demo\" 2>&1"));
        assertNull(CommandFailureTracker.hintFor(1));
        assertEquals(2, tracker.recordFailure("CMD /C java demo"));
        assertTrue(CommandFailureTracker.hintFor(2).contains("failed repeatedly"));
        assertEquals(3, tracker.recordFailure("java demo"));
        assertTrue(CommandFailureTracker.hintFor(3).contains("Do not retry it again"));
    }
}
