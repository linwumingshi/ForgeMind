package com.forgemind.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.model.ChatMessage;
import com.forgemind.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = TokenEstimator.DEFAULT;

    @Test
    void asciiIsRoughlyFourCharsPerToken() {
        // "hello world" = 11 chars → ceil(11/4) = 3
        assertEquals(3, estimator.estimate("hello world"));
        // 4 chars → 1 token；5 chars → 2
        assertEquals(1, estimator.estimate("abcd"));
        assertEquals(2, estimator.estimate("abcde"));
    }

    @Test
    void cjkIsRoughlyOneAndHalfCharsPerToken() {
        // 4 个汉字 → ceil(4*2/3)=3
        assertEquals(3, estimator.estimate("你好世界"));
        // 3 个汉字 → ceil(6/3)=2
        assertEquals(2, estimator.estimate("你好啊"));
    }

    @Test
    void mixedAsciiAndCjk() {
        String mixed = "hello 你好 world 世界";
        // ascii: "hello "=6 + " world "=7 + "" ≈ 13 → ceil(13/4)=4; cjk: 4 → 3; total ≥ 7
        assertTrue(estimator.estimate(mixed) >= 7);
    }

    @Test
    void emptyTextIsZeroButMessageIsNeverZero() {
        assertEquals(0, estimator.estimate(""));
        assertEquals(0, estimator.estimate((String) null));
        assertTrue(estimator.estimate(ChatMessage.user("")) >= 1);
    }

    @Test
    void messageOverheadAndContent() {
        long plain = estimator.estimate(ChatMessage.user("hello"));
        long longer = estimator.estimate(ChatMessage.user("hello world this is longer"));
        assertTrue(longer > plain);
    }

    @Test
    void toolCallsAddOverhead() {
        ChatMessage assistant = ChatMessage.assistantToolCalls(List.of(
                ToolCall.of("c1", "read_file", Map.of("path", "a.txt"))));
        ChatMessage noCalls = ChatMessage.assistant("just text");
        assertTrue(estimator.estimate(assistant) > estimator.estimate(noCalls));
    }

    @Test
    void toolMessageWithCallIdAddsOverhead() {
        ChatMessage tool = ChatMessage.tool("call-1", "some result");
        ChatMessage user = ChatMessage.user("some result");
        assertTrue(estimator.estimate(tool) > estimator.estimate(user));
    }

    @Test
    void listEstimateIsSum() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("task"),
                ChatMessage.tool("c1", "result"));
        long sum = estimator.estimate(ChatMessage.system("sys"))
                + estimator.estimate(ChatMessage.user("task"))
                + estimator.estimate(ChatMessage.tool("c1", "result"));
        assertEquals(sum, estimator.estimate(messages));
    }

    @Test
    void monotonicForLongerText() {
        String shortText = "short";
        String longText = shortText.repeat(100);
        assertTrue(estimator.estimate(longText) > estimator.estimate(shortText));
    }

    @Test
    void defaultIsApproximateEstimator() {
        assertTrue(TokenEstimator.DEFAULT instanceof ApproximateTokenEstimator);
    }
}
