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
}
