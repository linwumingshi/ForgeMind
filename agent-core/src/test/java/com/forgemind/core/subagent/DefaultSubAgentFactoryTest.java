package com.forgemind.core.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.fs.WorkspaceAccess;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.permission.PolicyPermissionManager;
import com.forgemind.core.testutil.EchoTool;
import com.forgemind.core.testutil.NamedTool;
import com.forgemind.core.testutil.StubLlmClient;
import com.forgemind.core.tool.InMemoryToolRegistry;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9.2：DefaultSubAgentFactory 安全/隔离不变量。
 */
class DefaultSubAgentFactoryTest {

    @TempDir
    Path tempDir;

    private InMemoryToolRegistry master;
    private WorkspaceAccess workspace;
    private DefaultSubAgentFactory factory;

    @BeforeEach
    void setUp() {
        master = new InMemoryToolRegistry();
        master.register(new EchoTool());
        master.register(new NamedTool("git_commit", PermissionScope.COMMIT));
        master.register(new NamedTool(DefaultSubAgentFactory.SUPERVISOR_TOOL, PermissionScope.READ));
        workspace = new WorkspaceAccess(tempDir);
        factory = new DefaultSubAgentFactory(tempDir, new StubLlmClient(),
                master, PolicyPermissionManager.withDefaults(), req -> true, workspace,
                AgentConfig.defaults());
    }

    private AgentResult run(StubLlmClient stub, SubAgentSpec spec) {
        return new DefaultSubAgentFactory(tempDir, stub, master,
                PolicyPermissionManager.withDefaults(), req -> true, workspace,
                AgentConfig.defaults()).run(spec);
    }

    @Test
    void inheritsAllToolsExceptSupervisor() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("sub done"));
        AgentResult result = run(stub, SubAgentSpec.of("subtask"));
        assertTrue(result.finished());
        // 子 loop 的 system prompt 只含 echo / git_commit，不含 sub_agent
        String system = stub.calls().get(0).get(0).content();
        assertTrue(system.contains("echo"));
        assertTrue(system.contains("git_commit"));
        assertFalse(system.contains(DefaultSubAgentFactory.SUPERVISOR_TOOL),
                "子 Agent 白名单必须强制排除 sub_agent");
    }

    @Test
    void restrictedWhitelistOnly() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("ok"));
        AgentResult result = run(stub, SubAgentSpec.of("subtask", List.of("echo")));
        assertTrue(result.finished());
        String system = stub.calls().get(0).get(0).content();
        assertTrue(system.contains("echo"));
        assertFalse(system.contains("git_commit"), "白名单外工具不得进入子 registry");
        assertFalse(system.contains(DefaultSubAgentFactory.SUPERVISOR_TOOL));
    }

    @Test
    void requestedUnknownToolIsHardRejected() {
        StubLlmClient stub = new StubLlmClient();
        AgentResult result = run(stub, SubAgentSpec.of("subtask", List.of("nope")));
        assertFalse(result.finished());
        assertTrue(result.error().contains("not available"));
        assertTrue(result.error().contains("nope"));
        assertEquals(0, stub.calls().size(), "白名单非法时不应运行子 loop");
    }

    @Test
    void requestedSupervisorToolIsHardRejected() {
        StubLlmClient stub = new StubLlmClient();
        AgentResult result = run(stub,
                SubAgentSpec.of("subtask", List.of(DefaultSubAgentFactory.SUPERVISOR_TOOL)));
        assertFalse(result.finished());
        assertTrue(result.error().contains("not allowed inside subagent"));
        assertEquals(0, stub.calls().size(), "请求 sub_agent 必须在创建时拒绝");
    }

    @Test
    void mixedWhitelistRejectsOnlyInvalid() {
        StubLlmClient stub = new StubLlmClient(AgentResponse.finalAnswer("ok"));
        AgentResult result = run(stub, SubAgentSpec.of("subtask", List.of("echo", "bad")));
        assertFalse(result.finished(), "白名单含非法项 → 整体拒绝（不静默裁剪）");
        assertTrue(result.error().contains("bad"));
    }

    @Test
    void subAgentCannotCallSupervisorTool() {
        // 子 loop 请求 sub_agent → registry 无此工具 → unknown tool 回灌 → 子自纠后完成
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", DefaultSubAgentFactory.SUPERVISOR_TOOL, Map.of("task", "x")))),
                AgentResponse.finalAnswer("self corrected"));
        AgentResult result = run(stub, SubAgentSpec.of("subtask", List.of("echo")));
        assertTrue(result.finished());
        assertEquals("self corrected", result.finalAnswer());
        // 第二轮 TOOL 消息包含 unknown tool（sub_agent 不可达）
        ChatMessage toolMsg = stub.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("unknown tool"));
        assertTrue(toolMsg.content().contains(DefaultSubAgentFactory.SUPERVISOR_TOOL));
    }

    @Test
    void subAgentToolCallsStillPassThroughPermissionManager() {
        // 子 Agent 请求 COMMIT 工具：PermissionManager 默认 COMMIT=ASK，answerer 拒绝 → permission denied
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "git_commit", Map.of("message", "x")))),
                AgentResponse.finalAnswer("adjusted"));
        DefaultSubAgentFactory denyingFactory = new DefaultSubAgentFactory(tempDir, stub, master,
                PolicyPermissionManager.withDefaults(), req -> false, workspace,
                AgentConfig.defaults());
        AgentResult result = denyingFactory.run(SubAgentSpec.of("subtask", List.of("git_commit")));
        assertTrue(result.finished());
        ChatMessage toolMsg = stub.calls().get(1).stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertTrue(toolMsg.content().contains("permission denied"),
                "子 Agent 的 COMMIT 权限仍须经 PermissionManager（不继承 READ/ALLOW）");
    }

    @Test
    void subAgentUsesSpecMaxIterations() {
        // 子预算 1：第二轮应触发 max iterations 而非继续
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "echo", Map.of("text", "a")))),
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "echo", Map.of("text", "b")))));
        AgentResult result = run(stub, new SubAgentSpec("subtask", List.of("echo"), 1));
        assertFalse(result.finished());
        assertTrue(result.error().contains("max iterations"));
    }

    @Test
    void subAgentInheritsMainMaxIterations() {
        // spec 未指定 → 继承主 config（默认 30），两轮工具执行完成
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "echo", Map.of("text", "a")))),
                AgentResponse.finalAnswer("done"));
        AgentResult result = run(stub, SubAgentSpec.of("subtask", List.of("echo")));
        assertTrue(result.finished());
        assertEquals("done", result.finalAnswer());
        assertEquals(2, result.iterations());
    }

    @Test
    void subAgentResultFailureReturnsWithoutThrowing() {
        StubLlmClient stub = new StubLlmClient(
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c1", "echo", Map.of("text", "a")))),
                AgentResponse.withToolCalls(null,
                        List.of(ToolCall.of("c2", "echo", Map.of("text", "b")))));
        AgentResult result = run(stub, new SubAgentSpec("subtask", List.of("echo"), 1));
        assertFalse(result.finished());
        assertTrue(result.error() != null && !result.error().isBlank());
    }
}
