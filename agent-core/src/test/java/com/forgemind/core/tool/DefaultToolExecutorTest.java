package com.forgemind.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PermissionDecision;
import com.forgemind.core.permission.PermissionRequest;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.testutil.EchoTool;
import com.forgemind.core.testutil.FailingTool;
import com.forgemind.core.testutil.PathTool;
import com.forgemind.model.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DefaultToolExecutor 完整链路测试：查找 → 校验 → 权限 → 执行。
 */
class DefaultToolExecutorTest {

    @TempDir
    Path tempDir;

    private InMemoryToolRegistry registry;
    private WorkspaceAccess workspace;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        workspace = new WorkspaceAccess(tempDir);
    }

    private DefaultToolExecutor executor(PolicyPermissionManager policy, PermissionAnswerer answerer) {
        return new DefaultToolExecutor(registry, policy, answerer, workspace);
    }

    private static final class RecordingAnswerer implements PermissionAnswerer {
        private final List<PermissionRequest> requests = new ArrayList<>();
        private final boolean answer;

        RecordingAnswerer(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean ask(PermissionRequest request) {
            requests.add(request);
            return answer;
        }
    }

    // ---------- 正常执行 ----------

    @Test
    void executesToolAndReturnsResult() {
        EchoTool echo = new EchoTool();
        registry.register(echo);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", Map.of("text", "hi"));
        assertTrue(result.success());
        assertEquals("echo: hi", result.output());
        assertEquals(1, echo.invocationCount());
    }

    @Test
    void nullArgumentsTreatedAsEmpty() {
        EchoTool echo = new EchoTool();
        registry.register(echo);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", null);
        assertFalse(result.success());
        assertTrue(result.error().contains("missing required argument 'text'"));
        assertEquals(0, echo.invocationCount());
    }

    // ---------- 未知 Tool ----------

    @Test
    void unknownToolReturnsFailureWithAvailableTools() {
        registry.register(new EchoTool());
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("nope", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("unknown tool"));
        assertTrue(result.error().contains("echo"));
    }

    // ---------- 参数校验 ----------

    @Test
    void missingRequiredArgumentFails() {
        registry.register(new EchoTool());
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("missing required argument 'text'"));
    }

    @Test
    void wrongTypeArgumentFails() {
        registry.register(new EchoTool());
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", Map.of("text", 123));
        assertFalse(result.success());
        assertTrue(result.error().contains("must be of type string"));
    }

    @Test
    void unknownArgumentsAreIgnored() {
        EchoTool echo = new EchoTool();
        registry.register(echo);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", Map.of("text", "hi", "extra", 1));
        assertTrue(result.success());
        assertEquals("echo: hi", result.output());
    }

    // ---------- 权限 ----------

    @Test
    void readScopeIsAllowedByDefault() {
        EchoTool echo = new EchoTool();
        registry.register(echo);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("echo", Map.of("text", "hi"));
        assertTrue(result.success());
        assertEquals(1, echo.invocationCount());
    }

    @Test
    void writeScopeAsksAndAllows() {
        EchoTool echo = new EchoTool(PermissionScope.WRITE);
        registry.register(echo);
        RecordingAnswerer answerer = new RecordingAnswerer(true);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), answerer)
                .execute("echo", Map.of("text", "hi"));
        assertTrue(result.success());
        assertEquals(1, echo.invocationCount());
        assertEquals(1, answerer.requests.size());
        assertEquals(PermissionScope.WRITE, answerer.requests.get(0).scope());
    }

    @Test
    void writeScopeAsksAndDenies() {
        EchoTool echo = new EchoTool(PermissionScope.WRITE);
        registry.register(echo);
        RecordingAnswerer answerer = new RecordingAnswerer(false);
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), answerer)
                .execute("echo", Map.of("text", "hi"));
        assertFalse(result.success());
        assertTrue(result.error().contains("permission denied"));
        assertEquals(0, echo.invocationCount());
    }

    @Test
    void overrideDenySkipsAnswerer() {
        EchoTool echo = new EchoTool(PermissionScope.WRITE);
        registry.register(echo);
        RecordingAnswerer answerer = new RecordingAnswerer(true);
        PolicyPermissionManager policy = PolicyPermissionManager.withDefaults()
                .withOverride("echo", PermissionDecision.DENY);
        ToolResult result = executor(policy, answerer).execute("echo", Map.of("text", "hi"));
        assertFalse(result.success());
        assertTrue(result.error().contains("permission denied"));
        assertEquals(0, echo.invocationCount());
        assertTrue(answerer.requests.isEmpty(), "DENY 时不应询问 Answerer");
    }

    @Test
    void permissionRequestCarriesPathDetail() {
        PathTool tool = new PathTool(PermissionScope.WRITE);
        registry.register(tool);
        RecordingAnswerer answerer = new RecordingAnswerer(true);
        executor(PolicyPermissionManager.withDefaults(), answerer)
                .execute("path_tool", Map.of("path", "pom.xml"));
        assertEquals("pom.xml", answerer.requests.get(0).detail());
    }

    // ---------- Tool 抛异常 ----------

    @Test
    void toolExceptionBecomesFailureResult() {
        registry.register(new FailingTool());
        ToolResult result = executor(PolicyPermissionManager.withDefaults(), req -> false)
                .execute("fail", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("boom"));
        assertNull(result.output());
    }
}
