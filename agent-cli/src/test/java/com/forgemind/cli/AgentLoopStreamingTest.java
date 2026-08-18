package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.loop.AgentLoop;
import com.forgemind.core.loop.ProgressListener;
import com.forgemind.llm.fake.FakeLlmClient;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentLoop Streaming 集成：stream → final；多 delta；与 chat 结果一致。
 */
class AgentLoopStreamingTest {

    @TempDir
    Path workspace;

    private AgentLoop buildAgent(FakeLlmClient fake, ProgressListener progress) {
        return com.forgemind.cli.AgentHarness.newLoop(workspace, fake,
                AgentConfig.defaults(), req -> false, progress);
    }

    @Test
    void streamToFinalAnswer() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("hello streaming"));
        AgentResult result = buildAgent(fake, ProgressListener.NOOP).run("task");
        assertTrue(result.finished());
        assertEquals("hello streaming", result.finalAnswer());
        assertEquals(1, result.iterations());
    }

    @Test
    void multipleDeltasAssembleFinalContent() {
        FakeLlmClient fake = new FakeLlmClient().then(AgentResponse.finalAnswer("你好世界"));
        List<String> deltas = new ArrayList<>();
        AgentResult result = buildAgent(fake, new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
                deltas.add(delta);
            }
        }).run("task");
        assertTrue(result.finished());
        assertEquals("你好世界", result.finalAnswer());
        assertEquals(4, deltas.size(), "Fake 应逐字符产生 4 个 text delta");
        assertEquals("你", deltas.get(0));
    }

    @Test
    void streamingResultMatchesChatResult() {
        // Fake 既是 chat 又是 stream 实现：stream 路径最终结果与脚本响应一致
        AgentResponse scripted = AgentResponse.finalAnswer("consistent answer");
        FakeLlmClient fake = new FakeLlmClient().then(scripted);
        AgentResult result = buildAgent(fake, ProgressListener.NOOP).run("task");
        assertEquals("consistent answer", result.finalAnswer());
    }
}
