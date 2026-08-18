# ForgeMind — 架构设计文档

> 版本：v0.1（MVP 设计稿）
> 状态：设计阶段，尚未编写任何代码
> 目标：从 0 到 1 构建一个类 Claude Code / Codex 的 Coding Agent

---

## 目录

1. [项目目标与 MVP 边界](#1-项目目标与-mvp-边界)
2. [总体架构](#2-总体架构)
3. [模块划分与依赖](#3-模块划分与依赖)
4. [核心接口设计](#4-核心接口设计)
5. [Agent Loop 时序与错误处理](#5-agent-loop-时序与错误处理)
6. [Tool System 设计](#6-tool-system-设计)
7. [Permission System 设计](#7-permission-system-设计)
8. [配置设计](#8-配置设计)
9. [异常体系](#9-异常体系)
10. [测试策略](#10-测试策略)
11. [MVP 开发计划](#11-mvp-开发计划)
12. [M0 – M10 开发路线图](#12-m0--m10-开发路线图)
13. [关键架构决策（ADR）](#13-关键架构决策adr)
14. [待确认的开放问题](#14-待确认的开放问题)

---

## 1. 项目目标与 MVP 边界

### 1.1 项目定位

`ForgeMind` 不是一个 LLM Chat，而是一个能自主完成编码任务的 Agent：

```
User → Agent → LLM → 判断是否需要 Tool
                      ├─ 需要 → ToolExecutor → Tool Result → 回到 LLM
                      └─ 不需要 → Final Answer
```

### 1.2 典型目标场景

用户输入 *"帮我给当前项目增加 User CRUD"*，Agent 应能自主完成：

1. 查看项目目录 → 2. 分析项目结构 → 3. 定位相关代码 → 4. 读取文件 →
5. 设计修改方案 → 6. 修改代码 → 7. 执行测试 → 8. 失败则分析错误 →
9. 再次修改 → 10. 再次测试 → 11. 测试通过后输出最终结论。

### 1.3 MVP 范围（第一阶段）

**实现：**

| 能力 | 说明 |
|---|---|
| CLI 交互模式 | picocli + REPL（`exit` / 任务输入 / `--yes` 自动批准等） |
| LLM 抽象 | `LlmClient` SPI，至少支持 OpenAI Compatible API（DeepSeek 可直接接入），可通过配置切换模型 |
| Tool System | `AgentTool` SPI + `ToolRegistry` + `ToolExecutor` |
| 6 个 Tool | `list_files` / `read_file` / `write_file` / `edit_file` / `search` / `shell` |
| Permission Manager | `ALLOW / ASK / DENY` 三态决策，写操作与 Shell 默认询问 |
| Agent Loop | 迭代执行 Tool 直到产出 Final Answer，含完整异常处理 |
| 单元测试 | 核心逻辑（Loop、Permission、Tool 边界）必须有测试 |

**不做（后续路线图覆盖）：** Git、MCP、SubAgent、RAG、Web UI、IDE Plugin、流式输出、上下文压缩、并行任务。

### 1.4 工程设计原则（贯穿始终）

1. 单一职责
2. 面向接口编程
3. Tool 必须插件化（可注册、可发现，Agent 不感知具体 Tool）
4. LLM Provider 必须可替换（自有 SPI，不绑定任何厂商 SDK）
5. Agent 与 Tool 解耦（Agent 只依赖 `ToolRegistry` / `ToolExecutor`）
6. Permission 与 Tool 解耦（Tool 只声明权限范围，决策由执行器/策略完成）
7. CLI 与 Agent Core 解耦（Core 无任何控制台/Spring 依赖）
8. API Key 禁止硬编码（环境变量注入 + 日志脱敏）
9. Tool 禁止直接操作任意文件系统（统一经 `WorkspaceAccess` 根目录围栏）
10. Shell 禁止无限执行（强制 timeout + 输出截断 + 迭代预算）
11. 所有关键行为必须有日志（循环迭代、Tool 调用、权限决策、异常）
12. 核心逻辑必须有单元测试

---

## 2. 总体架构

### 2.1 分层视图

```
┌─────────────────────────────────────────────────────────┐
│                     agent-cli (组合根)                    │
│   picocli Main → Spring Boot (NONE) → REPL               │
│   ├─ 装配 Agent / Tools / LlmClient / Permission          │
│   ├─ 交互式权限应答 (y/N 提示)                            │
│   └─ 加载 application.yml → AgentConfig                   │
├─────────────────────────────────────────────────────────┤
│                      agent-core (编排核心)                │
│   Agent / AgentLoop / AgentContext                       │
│   LlmClient (SPI) · AgentTool (SPI)                      │
│   ToolRegistry · ToolExecutor · PermissionManager        │
│   WorkspaceAccess (路径围栏) · 异常体系 · 配置模型          │
├──────────────────────┬──────────────────────────────────┤
│      agent-llm       │            agent-tools            │
│  LlmClient 实现      │   AgentTool 实现 (6 个)            │
│  ├ OpenAiCompatible │   ├ list_files / read_file         │
│  │ (Spring AI 适配) │   ├ write_file / edit_file         │
│  ├ FakeLlmClient    │   ├ search / shell                 │
│  └ (测试用)          │   └ ShellProvider (cmd/powershell) │
├──────────────────────┴──────────────────────────────────┤
│                    agent-model (纯数据)                   │
│  ChatMessage / ToolCall / ToolResult / AgentResponse     │
│  ToolSchema / ToolParameter / ...  (零业务依赖)           │
└─────────────────────────────────────────────────────────┘
```

### 2.2 依赖方向（唯一原则：依赖只朝下）

```
agent-cli ──→ agent-llm, agent-tools, agent-core, agent-model
agent-llm ──→ agent-core ──→ agent-model
agent-tools ─→ agent-core ──→ agent-model
（agent-model 不依赖任何模块）
```

- **任何模块都不反向依赖 agent-cli**：CLI 只是组合根，可整体替换为 Web UI / IDE Plugin / 测试驱动入口。
- **agent-core 不依赖 Spring / Spring AI / picocli**：核心逻辑可用纯 JUnit 独立测试，未来可脱离 Boot 复用。

### 2.3 运行期对象图（核心类关系）

```
Main (picocli)
 └─ AgentCliApplication (Spring Boot, WebApplicationType.NONE)
     └─ Repl ────────────────► DefaultAgent
                                └─ AgentLoop
                                    ├─► LlmClient (接口)
                                    │    ├─ OpenAiCompatibleLlmClient  (agent-llm)
                                    │    └─ FakeLlmClient              (agent-llm, 测试)
                                    ├─► ToolRegistry (接口)
                                    │    └─ InMemoryToolRegistry ──持有──► AgentTool 实例 (6 个, agent-tools)
                                    ├─► ToolExecutor (接口)
                                    │    └─ DefaultToolExecutor
                                    │         ├─► PermissionManager (接口)
                                    │         │    └─ PolicyPermissionManager ──► PermissionAnswerer (CLI 交互/自动)
                                    │         └─► WorkspaceAccess (路径围栏)
                                    ├─► AgentContext
                                    └─► AgentConfig (迭代预算/超时/大小上限)
```

---

## 3. 模块划分与依赖

### 3.1 最终模块结构

```
ForgeMind
├── pom.xml                 # 父 POM：聚合 + 依赖版本管理 (BOM)
├── agent-model/            # 纯数据模型（零依赖）
├── agent-core/             # 核心接口 + Agent/AgentLoop + 权限 + 配置 + 异常
├── agent-llm/              # LlmClient 实现（OpenAI Compatible / Fake）
├── agent-tools/            # 6 个 AgentTool 实现 + ShellProvider
├── agent-cli/              # picocli + Spring Boot 组合根 + REPL
├── docs/
│   └── architecture.md     # 本文档
└── README.md
```

### 3.2 为什么是 4+1 个模块（决策说明）

需求建议 4 个模块（core/model/tools/cli）。实际分析依赖关系后，**在建议基础上增加 `agent-llm`**：

| 模块 | 职责 | 依赖 | 不这样拆的后果 |
|---|---|---|---|
| `agent-model` | 纯数据：消息、Tool 调用/结果、Schema | 仅 Jackson 注解 | 与 core 合并会破坏"core 无框架依赖"的纯度 |
| `agent-core` | 接口与编排：Agent、AgentLoop、SPI、权限、围栏、异常、配置 | model, slf4j, Jackson | — |
| `agent-llm` | LlmClient 实现；**唯一允许触碰 Spring AI / HTTP 的模块** | core, model, Spring AI | 若放入 core，核心将依赖具体厂商/HTTP 实现，违反原则 4 |
| `agent-tools` | 6 个 Tool + Shell 进程封装 | core, model | 若放入 core，新增 Tool 就必须改核心模块，违反插件化 |
| `agent-cli` | 组合根：装配 + REPL + 权限交互 + 配置加载 | 全部 | 若拆分到其他模块，CLI 与 Core 无法解耦（原则 7） |

**不合并的理由（MVP 宁愿简单，但"简单"不等于"塞进一个模块"）：**
- 依赖方向单向、清晰，每个模块边界都对应一个明确的"换人"诉求（换 LLM / 加 Tool / 换入口）。
- 4 个模块是需求方建议的最小集，+1（agent-llm）是唯一需要增补的，因为它解决的是"core 不得依赖 HTTP/厂商"这一硬约束。

### 3.3 模块内包结构（草案）

```
agent-core:
  agent.core.Agent / DefaultAgent
  agent.core.loop.AgentLoop
  agent.core.context.AgentContext / ToolContext
  agent.core.llm.LlmClient
  agent.core.tool.AgentTool / ToolRegistry / InMemoryToolRegistry / ToolExecutor / DefaultToolExecutor / ToolSchema...
  agent.core.permission.PermissionManager / PermissionScope / PermissionRequest / PermissionDecision / PermissionAnswerer / PolicyPermissionManager
  agent.core.fs.WorkspaceAccess
  agent.core.config.AgentConfig / LlmConfig / PermissionConfig / ToolLimits
  agent.core.exception.*
agent-tools:
  agent.tools.fs.ListFilesTool / ReadFileTool / WriteFileTool / EditFileTool
  agent.tools.search.SearchTool
  agent.tools.shell.ShellTool / ShellProvider / CmdShellProvider / PowerShellShellProvider
agent-llm:
  agent.llm.openai.OpenAiCompatibleLlmClient
  agent.llm.fake.FakeLlmClient
agent-cli:
  agent.cli.Main / AgentCliApplication / Repl / InteractivePermissionAnswerer
  agent.cli.config.ConfigLoader
```

---

## 4. 核心接口设计

> 以下为设计签名（草案）。**本阶段仅作设计，不创建 Java 文件。**

### 4.1 `Agent`（对外门面）

```java
public interface Agent {
    /** 同步执行一个任务，返回最终结果。CLI/Web/测试共用的入口。 */
    AgentResult run(String task);
}

public record AgentResult(
    String finalAnswer,     // 最终结论
    int iterations,         // 实际循环轮数
    int toolCallCount,      // 实际 Tool 调用次数
    boolean finished,       // 是否正常结束（false = 预算耗尽/异常终止）
    String error            // 非正常结束时携带原因
) {}
```

设计说明：`Agent` 是唯一对外入口；`DefaultAgent` 内部委托 `AgentLoop`，便于未来替换实现（例如异步版）。

### 4.2 `AgentLoop`（核心循环）

```java
public class AgentLoop {
    public AgentLoop(LlmClient llm, ToolRegistry registry,
                     ToolExecutor executor, PermissionManager permission,
                     AgentContext context, AgentConfig config) { ... }

    public AgentResult run(String task) { ... }
}
```

职责：
- 组装 System Prompt（角色、工作目录、Tool 说明、规则）并维护对话历史；
- 迭代调用 `LlmClient.chat(messages)`；
- 响应含 Tool Call 时：逐条执行 → 结果回灌历史 → 继续；
- 响应为纯文本时：结束，返回 Final Answer；
- 统一兜底：迭代预算、非法 Tool Call、LLM 异常（详见 §5）。

### 4.3 `AgentContext`（上下文）

```java
public class AgentContext {
    private final Path workingDirectory;   // 工作目录（所有路径的根）
    private final String currentTask;      // 当前任务描述
    private final List<ChatMessage> conversation; // 完整对话历史
    // 扩展槽（本阶段留空，不实现）：
    //   tokenUsage / summary / memory / projectIndex
}
```

设计说明：
- 采用**可变状态 + 显式访问器**（而非纯 record），因为 conversation 需要被 Loop 持续追加；
- `workingDirectory` 一旦创建即不可变，作为路径围栏的锚点；
- 后续扩展字段以"预留接口 + 空实现"方式加入，不在 MVP 引入复杂度。

### 4.4 `LlmClient`（LLM 抽象，自有 SPI）

```java
public interface LlmClient {
    /** 厂商/实现标识，如 "openai-compatible"、"fake" */
    String provider();

    /** 发送完整消息历史，返回结构化响应（含 Tool Calls 或最终文本） */
    AgentResponse chat(List<ChatMessage> messages);

    // 未来扩展：streaming 变体、token 统计、模型名查询
}
```

设计说明：
- **不绑定任何厂商 SDK**。OpenAI / Anthropic / DeepSeek / 任何 OpenAI-Compatible 服务都只是该接口的一个实现；
- `AgentLoop` 只依赖该接口，模型切换 = 配置切换（§8）；
- `messages` 由 Loop 持有（在 `AgentContext` 中），`LlmClient` 保持无状态、可复用；
- 实现侧在 `agent-llm`：MVP 用 **Spring AI 2.x 的 OpenAI ChatModel 作为 OpenAI-Compatible 传输层**，外面包一层薄适配器；若 Spring AI 的 Tool Calling 消息模型带来摩擦，可随时替换适配器内部为 Spring `RestClient` 直连 `/chat/completions`，**不触碰 core**（见 ADR-03）。

### 4.5 `AgentTool`（Tool SPI）

```java
public interface AgentTool {
    /** 唯一名称，LLM 通过它调用，如 "read_file" */
    String name();

    /** 给 LLM 看的能力描述 */
    String description();

    /** JSON Schema 形式的参数定义（用于注入 LLM tools 参数） */
    ToolSchema schema();

    /** 声明本工具需要的权限范围（READ/WRITE/SHELL），由执行器决定如何处理 */
    PermissionScope permissionScope();

    /** 真正的执行逻辑。context 提供受限的文件系统访问与限额。 */
    ToolResult execute(ToolContext context, Map<String, Object> arguments)
            throws ToolExecutionException;
}
```

设计说明：
- **Tool 不知道任何权限决策逻辑**，只声明 `PermissionScope`（原则 6）；
- **Tool 不直接拿绝对路径操作文件系统**，一律通过 `ToolContext` 暴露的 `WorkspaceAccess`（原则 9）；
- 每个 Tool 是独立类，通过 Spring 组件扫描或手动注册进入 `ToolRegistry`（插件化）。

### 4.6 `ToolRegistry`（工具注册表）

```java
public interface ToolRegistry {
    void register(AgentTool tool);
    Optional<AgentTool> find(String name);   // 未知工具 → empty
    Map<String, AgentTool> all();
    int size();
}
// 默认实现：InMemoryToolRegistry（ConcurrentHashMap 包装）
```

### 4.7 `ToolExecutor`（工具执行器）

```java
public interface ToolExecutor {
    /** 校验 → 权限决策 → 执行 → 统一捕获异常/超时 → 返回 ToolResult */
    ToolResult execute(String toolName, Map<String, Object> arguments);
}
```

`DefaultToolExecutor` 内部顺序：

```
1. 查注册表         → 未知工具 → 返回"未知工具"错误结果（不抛异常给 Loop）
2. 参数校验(schema) → 参数错误 → 返回带校验详情的错误结果
3. 构造 PermissionRequest（scope + path/command 细节）
4. permission.decide(request)
     ├─ ALLOW → 继续
     ├─ ASK   → answerer 询问；拒绝则返回 PermissionDenied 错误结果
     └─ DENY  → 直接返回 PermissionDenied 错误结果
5. 执行（带 per-tool timeout）→ 超时 kill 进程并返回超时错误结果
6. 输出截断（stdout/stderr/文件内容超限截断 + truncated 标记）
7. 返回 ToolResult
```

设计说明：**所有"失败"都以 `ToolResult`（成功=false + 错误信息）返回，而非抛异常打断循环** —— 这样 LLM 能读到错误并自我纠正；异常只在极少数不可恢复场景（如配置错误）向 Loop 抛出。

### 4.8 `PermissionManager`（权限决策）

```java
public interface PermissionManager {
    PermissionDecision decide(PermissionRequest request);
}

public enum PermissionDecision { ALLOW, ASK, DENY }

public record PermissionRequest(
    PermissionScope scope,    // READ / WRITE / SHELL
    String toolName,          // 发起方
    String description,       // 人类可读描述，如 "写入文件 src/UserController.java"
    String detail             // 关键载荷：目标路径 / shell 命令
) {}

public enum PermissionScope { READ, WRITE, SHELL }
```

默认实现 `PolicyPermissionManager`：
- 从配置读取各 scope 的默认策略（`read=allow, write=ask, shell=ask`，可覆盖）；
- READ → 直接 ALLOW；WRITE / SHELL → 返回 ASK，由外部 `PermissionAnswerer` 完成人机交互；
- 所有决策写日志（原则 11）。

`PermissionAnswerer`（决策如何落地，注入式，方便测试与 CI）：

```java
public interface PermissionAnswerer {
    boolean ask(PermissionRequest request);   // true=允许
}
```

CLI 提供 `InteractivePermissionAnswerer`（渲染 `Allow? [y/N]`）；`--yes` 模式提供 `AutoAllowAnswerer`；CI 提供 `AutoDenyAnswerer`。

### 4.9 `WorkspaceAccess`（路径围栏，安全边界实现）

```java
public class WorkspaceAccess {
    public WorkspaceAccess(Path workspaceRoot) { ... }
    /** 解析用户/LLM 给出的路径 → 规范化 → 校验必须在根内 → 返回绝对 Path */
    public Path resolve(String path) throws PathEscapeException;
    public boolean isInside(Path path);
    // 底层读写能力（供 Tool 使用）：readAllBytes / write / walk / matches ...
}
```

强制规则（对 list/read/write/edit/search 全部生效）：
1. 相对路径以工作目录为根解析，绝对路径必须位于工作目录内；
2. `normalize()` 后必须 `startsWith(root)`，拒绝 `..` 逃逸；
3. 若路径已存在，再做 `toRealPath()` 二次校验（防符号链接逃逸；MVP 尽力而为，作为已知限制记录）；
4. 拒绝根目录本身作为写入目标等危险用例。

---

## 5. Agent Loop 时序与错误处理

### 5.1 时序图

```
User   CLI/Repl        AgentLoop          LlmClient      ToolExecutor   PermissionMgr   Tool
 │        │               │                  │               │              │           │
 │ task   │               │                  │               │              │           │
 ├───────►│               │                  │               │              │           │
 │        │ run(task)     │                  │               │              │           │
 │        ├──────────────►│ 组装 system prompt│               │              │           │
 │        │               │─────────────────►│ chat(messages) │              │           │
 │        │               │                  │────POST──────►│ (LLM API)    │           │
 │        │               │◄── tool_calls ───│                │              │           │
 │        │               │ 或 content       │               │              │           │
 │        │               │                  │               │              │           │
 │        │      loop 每  │ 对每个 tool_call: │               │              │           │
 │        │      个调用    │ execute(name,args)│              │              │           │
 │        │               │─────────────────────────────────►│              │           │
 │        │               │                  │               │ decide(req)  │           │
 │        │               │                  │               ├─────────────►│           │
 │        │               │                  │               │◄─ ALLOW/ASK ─│           │
 │        │               │                  │               │  (ASK→用户 y/N)│          │
 │        │               │                  │               │─────────────►│ execute   │
 │        │               │                  │               │◄── ToolResult│           │
 │        │               │◄── ToolResult ───│               │              │           │
 │        │               │ 追加到对话历史    │               │              │           │
 │        │               │─────────────────►│ chat(messages+结果)          │           │
 │        │               │◄── content ──────│               │              │           │
 │        │◄─ AgentResult─│                  │               │              │           │
 │◄───────│               │                  │               │              │           │
```

### 5.2 伪代码

```
AgentResult run(task):
    context.begin(task)                                  # 记录 workingDirectory + currentTask
    messages = [systemPrompt(task, tools), user(task)]
    finished = false
    while !finished:
        if iterations >= config.maxIterations:  throw MaxIterationsExceeded
        response = llm.chat(messages)
        if response.hasToolCalls():
            for call in response.toolCalls():
                result = executor.execute(call.name, call.arguments)   # 内部完成一切兜底
                messages.add(toolResultMessage(call.id, result))
            iterations += 1
            continue
        finished = true
        return AgentResult(response.content, iterations, toolCalls, true, null)
```

### 5.3 异常/边界处理矩阵（核心设计点）

| 场景 | 处理方式 | 后果 |
|---|---|---|
| LLM 返回未知 Tool 名 | 以错误 ToolResult 回灌（"未知工具 xxx，可用：…"） | 循环继续，LLM 可纠正 |
| Tool 参数缺/错 | 按 schema 校验，返回带校验详情的错误 ToolResult | 循环继续 |
| Tool 执行抛异常 | 捕获 → 错误 ToolResult（含堆栈摘要） | 循环继续 |
| 权限 DENY / 用户拒绝 | 错误 ToolResult："权限被拒绝，原因/可选项" | 循环继续（LLM 可换方案） |
| Tool 超时 | kill 进程 → 错误 ToolResult："超时(timeout)" | 循环继续 |
| 输出超限 | 截断 + `truncated=true` 标记 | 循环继续 |
| LLM API 异常（网络/5xx/限流） | 指数退避重试（≤3 次），仍失败 → 抛 `LlmException` | **终止**（不可恢复） |
| LLM 返回非法 JSON / 连续畸形 Tool Call | 首次回灌"格式错误"错误结果；连续 ≥3 次 → 抛 `InvalidToolCallException` | 有自我纠正机会，防死循环 |
| 迭代次数超预算 | 抛 `MaxIterationsExceededException` | **终止**，返回部分成果+原因 |
| 用户 Ctrl+C | CLI 捕获中断信号 → 优雅终止，返回部分结果 | 终止 |

**核心原则：凡是"LLM 能读懂并纠正"的错误 → 回灌为 ToolResult 让循环继续；凡是"环境/配置不可恢复"的错误 → 抛异常终止。** 两条路径都有预算与重试兜底，杜绝无限循环（原则 10）。

### 5.4 预算控制（三个维度）

| 预算 | 默认值 | 配置项 |
|---|---|---|
| 最大循环轮数 | 30 | `agent.max-iterations` |
| 单 Tool 超时 | 60s（shell）/ 10s（文件类） | `agent.tools.timeout.*` |
| 单条消息/Tool 结果输出上限 | 64 KB | `agent.tools.output-limit` |

---

## 6. Tool System 设计

### 6.1 Tool Calling 数据结构（内部 Java 模型，agent-model）

```java
public record ToolCall(
    String id,                      // LLM 分配的唯一调用 ID（回灌 tool 结果时对应）
    String name,                    // 工具名，如 "read_file"
    Map<String, Object> arguments   // 参数（反序列化后的 JSON 对象）
) {}

public record ToolResult(
    String toolCallId,              // 对应 ToolCall.id
    boolean success,
    String output,                  // 主输出（stdout / 文件内容 / 操作结果摘要）
    String error,                   // 失败时的错误信息
    Integer exitCode,               // 仅 shell 使用
    boolean truncated               // 输出是否被截断
) {}

public record AgentResponse(
    String content,                 // 非 Tool 响应时 = 最终答案 / 中间思考
    List<ToolCall> toolCalls,       // 非空 = 需要执行工具
    boolean finished                // = toolCalls.isEmpty() && content != null
) { boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); } }

public record ChatMessage(
    Role role,                      // SYSTEM / USER / ASSISTANT / TOOL
    String content,                 // 文本内容（tool 角色时为结果文本）
    String toolCallId,              // 仅 TOOL 角色
    List<ToolCall> toolCalls        // 仅 ASSISTANT 角色（LLM 返回的调用）
) {}

public record ToolSchema(
    String type,                    // 恒为 "object"
    Map<String, ToolParameter> properties,
    List<String> required
) {}

public record ToolParameter(String type, String description) {}
```

### 6.2 对外传输格式（OpenAI Compatible JSON，LLM 侧）

发送给 LLM 的 `tools` 数组（由 `ToolSchema` 序列化）：

```json
{
  "type": "function",
  "function": {
    "name": "read_file",
    "description": "读取工作区内文本文件（UTF-8）",
    "parameters": {
      "type": "object",
      "properties": { "path": { "type": "string", "description": "文件路径" } },
      "required": ["path"]
    }
  }
}
```

LLM 返回 Tool Call（assistant 消息）：

```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_01",
      "type": "function",
      "function": { "name": "read_file", "arguments": "{\"path\":\"pom.xml\"}" }
    }
  ]
}
```

Agent 回灌执行结果（tool 消息）：

```json
{ "role": "tool", "tool_call_id": "call_01", "content": "<ToolResult 渲染后的文本>" }
```

> MVP 约定：`ToolResult` 统一渲染为**纯文本**回灌 LLM（简单、兼容所有模型）；结构化 JSON 结果作为后续增强（M5+）。

### 6.3 六个 Tool 的规格

| Tool | 参数 | 权限 | 关键行为约束 |
|---|---|---|---|
| `list_files` | `path`（可空=根）、`recursive`(默认 false)、`maxDepth`(≤3) | READ | 单层列出 name/type/size/mtime；结果上限 500 条；`recursive` 时受 maxDepth 限制（**不允许无限递归**） |
| `read_file` | `path` | READ | UTF-8；不存在→明确错误；二进制检测（前 8KB 含 NUL 或已知二进制扩展名）→拒绝；大小上限默认 1 MB，超限报错（不截断，提示用 search/分段） |
| `write_file` | `path`、`content` | WRITE | 经 `WorkspaceAccess` 围栏；自动创建父目录；覆盖语义明确返回"已写入 N 字节" |
| `edit_file` | `path`、`oldText`、`newText` | WRITE | oldText 必须存在；**默认要求唯一匹配**，多处匹配→报错列出位置数；写入采用"先读→匹配→临时文件替换"保证失败不破坏原文件；oldText 上限 64 KB |
| `search` | `query`、`path`(可空) | READ | 第一版 Java 递归实现：大小写不敏感子串匹配；跳过 `.git/node_modules/target/build` 等；跳过二进制；结果上限 200 条，带行号与 ±2 行上下文；检测到 `rg` 时自动切换 ripgrep（后续） |
| `shell` | `command` | SHELL | 必须经权限确认；强制 timeout（默认 60s，超时 `destroyForcibly` + Windows 下 `taskkill /T /F` 杀进程树）；stdout/stderr 各上限 64 KB；返回 exitCode + stdout + stderr + truncated |

### 6.4 Shell 的 Windows 设计（`ShellProvider`）

```
ShellProvider (接口)
 ├── CmdShellProvider        # 默认：cmd.exe /C "<command>"，兼容 mvn/git 等
 └── PowerShellShellProvider # 配置 agent.shell.type=powershell 时启用
```

- 工作目录固定为 `workingDirectory`（与文件围栏一致）；
- 统一走 `ProcessBuilder` + 有界输出捕获器（读满上限即停止累积并标记 truncated）；
- 超时：`waitFor(timeout)` 失败后 `destroyForcibly()`，并尝试 `taskkill /PID <pid> /T /F` 确保子进程树被杀；
- 环境变量透传当前进程（继承），不额外注入敏感信息。

---

## 7. Permission System 设计

### 7.1 三态决策模型

```
PermissionRequest(scope, toolName, description, detail)
        │
        ▼
PolicyPermissionManager.decide()
        │
        ├─ scope == READ        → ALLOW（list/read/search 自动放行）
        ├─ scope == WRITE/SHELL → ASK（默认），由 PermissionAnswerer 决策
        └─ 配置可覆盖：allow/deny/ask 按 scope 或按工具名精确配置
```

### 7.2 决策链路（谁负责什么）

| 角色 | 职责 |
|---|---|
| Tool | 只声明 `PermissionScope`，不接触任何决策逻辑（**Permission 与 Tool 解耦**） |
| ToolExecutor | 在执行前构造 `PermissionRequest`（从参数提取 path/command）并调用 decide |
| PolicyPermissionManager | 按策略返回 ALLOW / ASK / DENY；记录审计日志 |
| PermissionAnswerer | 把 ASK 落地为人类交互（CLI 的 `Allow? [y/N]`）或自动化策略（`--yes` / CI deny） |

### 7.3 CLI 交互形态

```
Agent wants to execute:

    mvn test

Allow? [y/N] y
```

- 默认 `N`（安全默认）；
- `--yes` 全局自动允许（用户显式声明信任）；
- 拒绝后 LLM 收到"权限被拒绝"ToolResult，可自主调整方案（如改读只读信息）。

### 7.4 安全边界总结（原则 9/10 落地）

- **文件系统**：所有 Tool 经 `WorkspaceAccess`，越界即拒绝，与权限系统无关（权限系统管"该不该做"，围栏管"能不能碰到"）；
- **Shell**：命令级人审 + timeout + 输出截断 + 进程树杀灭；
- **API Key**：仅环境变量/配置占位符注入，日志过滤器脱敏（正则替换 `sk-...` 等模式）。

---

## 8. 配置设计

### 8.1 配置文件（`agent-cli/src/main/resources/application.yml`）

```yaml
agent:
  working-directory: "."            # 默认当前目录
  max-iterations: 30                # 循环预算

  llm:
    provider: openai-compatible     # openai-compatible | fake(测试)
    base-url: ${AGENT_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${AGENT_API_KEY}       # 仅环境变量注入，禁止硬编码
    model: deepseek-chat
    temperature: 0.2
    max-tokens: 8192
    # 未来: anthropic / openai / deepseek 各有独立配置块

  permission:
    defaults:                       # 按 scope 的默认策略
      read: allow
      write: ask
      shell: ask
    overrides:                      # 按工具名的精确覆盖（可选）
      # "shell": deny

  tools:
    output-limit: 64kb              # Tool 结果输出上限
    read-file:
      max-bytes: 1mb
    edit-file:
      old-text-max: 64kb
    search:
      max-results: 200
      ignore: [".git", "node_modules", "target", "build", ".idea"]
    shell:
      type: cmd                     # cmd | powershell
      timeout: 60s

  logging:
    level: info
```

### 8.2 配置模型（agent-core，纯 record，Jackson 反序列化）

```
AgentConfig { workingDirectory, maxIterations, LlmConfig llm, PermissionConfig permission, ToolLimits tools }
LlmConfig { provider, baseUrl, apiKey, model, temperature, maxTokens }
PermissionConfig { Map<PermissionScope,Decision> defaults, Map<String,Decision> overrides }
ToolLimits { outputLimit, readFileMaxBytes, editFileOldTextMax, searchMaxResults, ignoreList, ShellConfig shell }
```

- 绑定方式：`agent-cli` 用 Jackson YAML（`jackson-dataformat-yaml`）加载 → 校验 → 构建 `AgentConfig`；
- 环境变量占位符（`${AGENT_API_KEY}`）在加载时展开；启动时检查 api-key 缺失则明确报错退出；
- **core 不依赖 Spring**，因此不用 `@ConfigurationProperties`，配置模型是纯 POJO/record（可独立单测）。

---

## 9. 异常体系

```
AgentException (RuntimeException, 基类)
├── LlmException                      # LLM API 不可恢复错误（网络/鉴权/5xx 重试耗尽）
├── InvalidToolCallException         # LLM 返回非法 Tool Call（连续畸形）
├── MaxIterationsExceededException   # 循环预算耗尽
├── ConfigException                  # 配置缺失/非法（如无 api-key）
└── ToolException (基类)
    ├── UnknownToolException         # 注册表查不到（默认转为错误 ToolResult，不抛出）
    ├── InvalidToolArgumentsException# schema 校验失败（默认转为错误 ToolResult）
    ├── ToolExecutionException       # 工具执行内部失败（默认转为错误 ToolResult）
    ├── ToolTimeoutException         # 超时（默认转为错误 ToolResult）
    └── PathEscapeException          # WorkspaceAccess 越界（安全错误，必须日志告警）
```

**分层策略：**
- 上层的 `*Exception`（Llm/MaxIterations/InvalidToolCall）由 `AgentLoop` 捕获 → 封装进 `AgentResult.error` 返回，**绝不裸抛给用户**；
- 下层的 `ToolException` 系默认在 `DefaultToolExecutor` 内被吞并转为错误 `ToolResult`（LLM 可读），只有 `PathEscapeException` 同时强制记 WARN 日志（安全事件）；
- 所有异常必须有 message 且可读，异常链保留堆栈用于诊断日志。

---

## 10. 测试策略

### 10.1 分层测试

| 层 | 焦点 | 手段 |
|---|---|---|
| agent-model | 序列化/反序列化、schema 校验 | JUnit 5 + Jackson 单测 |
| agent-core | **最高优先级**：AgentLoop 全流程、ToolExecutor 兜底、Permission 策略、WorkspaceAccess 围栏 | JUnit 5 + `FakeLlmClient` + `@TempDir` 临时目录 |
| agent-tools | 每个 Tool 的边界行为 | JUnit 5 + `@TempDir` |
| agent-llm | OpenAI-Compatible 协议编解码 | 用 WireMock 桩 HTTP 接口（不真实调用 LLM） |
| agent-cli | REPL 命令分发、权限交互、配置加载 | 管道喂入 stdin 的集成测试 |

### 10.2 关键测试用例清单（MVP 必须覆盖）

**AgentLoop（用 `FakeLlmClient` 脚本化响应序列）：**
1. 单轮直接返回 Final Answer（无 Tool Call）；
2. 一轮 Tool Call → 结果回灌 → Final Answer（Happy Path）；
3. 多轮 Tool 链（如 read → edit → shell test → Final）；
4. 未知工具名 → 错误结果回灌 → LLM 纠正；
5. 参数错误 → schema 校验错误回灌；
6. 权限 DENY → 错误结果回灌，循环继续；
7. Tool 超时 → 超时错误回灌；
8. 连续 3 次畸形响应 → `InvalidToolCallException`；
9. 迭代预算耗尽 → `MaxIterationsExceededException` + 部分结果；
10. LLM API 抛错且重试耗尽 → `LlmException`。

**WorkspaceAccess（安全）：**
- 相对路径解析、`../` 逃逸、绝对路径越界、符号链接逃逸（尽力）、根目录写保护。

**EditFileTool：**
- oldText 不存在 / 唯一匹配 / 多处匹配（必须报错且不改文件）/ 失败后原文件字节不变。

**Permission：**
- 策略表驱动：read=allow、write=ask、shell=ask、overrides 覆盖生效；ASK→answerer 拒绝→DENY。

**ShellTool：**
- 正常命令 exitCode/stdout/stderr；超时命令被杀且返回超时；输出超限截断标记。

### 10.3 测试替身约定

| 替身 | 用途 |
|---|---|
| `FakeLlmClient` | 从脚本队列依次返回预设 `AgentResponse`，可断言收到的消息序列 |
| `@TempDir` | 每个测试独立临时工作目录，避免污染真实目录 |
| `WireMock`（agent-llm） | 桩 /chat/completions 端点，测协议与重试逻辑 |
| `InMemoryPermissionAnswerer` | 可编程返回 allow/deny，测试决策链路 |

### 10.4 质量门槛（MVP 完成标准）

- `agent-core` 行覆盖率 ≥ 80%（重点 Loop 与权限）；
- 6 个 Tool 的边界用例全绿；
- `mvn test` 全绿 + `mvn verify` 通过；
- 关键路径日志齐全（迭代、Tool 调用、权限决策、异常）。

---

## 11. MVP 开发计划

> 目标：交付一个可交互使用的 CLI Agent（真实 LLM 可跑通 "分析项目 → 改代码 → 跑测试" 闭环）。计划按里程碑推进，每步可独立验收。

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0 骨架** | 父 POM + 5 个模块空壳；SLF4J + Logback；CI 能 `mvn package` | 空项目构建通过 |
| **M1 数据与核心接口** | agent-model 全部 record；agent-core 全部接口 + 默认实现（Registry/Executor/PolicyPermissionManager）+ WorkspaceAccess + 异常体系 | 单测全绿（尤其围栏） |
| **M2 六个 Tool** | 6 个 AgentTool 实现 + ShellProvider(cmd/powershell) + 限额/超时/截断 | 每个 Tool 边界测试全绿 |
| **M3 Agent Loop + Fake LLM** | AgentLoop + FakeLlmClient + AgentResult；错误处理矩阵全部落地；迭代预算 | Loop 10 个关键用例全绿 |
| **M4 真实 LLM + CLI** | OpenAiCompatibleLlmClient（Spring AI 适配）；picocli REPL（exit/任务/--yes/--working-dir）；配置加载 + 环境变量注入；InteractivePermissionAnswerer | 用 DeepSeek/任意 OpenAI-Compatible key 手工跑通端到端示例任务 |
| **M5 MVP 打磨** | 日志完善与脱敏；输出截断体验；错误提示优化；README 快速开始 | 非开发者按 README 可 10 分钟跑通 |

**依赖顺序：** M0 → M1 → M2（可与 M3 并行）→ M3 → M4 → M5。

**MVP 主要风险与对策：**

| 风险 | 对策 |
|---|---|
| Spring AI 2.x Tool Calling 消息模型不合用 | ADR-03 已隔离：仅替换适配器内部为 RestClient 直连 |
| 不同模型 Tool Calling 格式差异 | 统一走 OpenAI-Compatible 格式；LlmClient 层做归一化 |
| Shell 超时杀不干净子进程（Windows） | taskkill /T /F 进程树 + 验收用例覆盖 |
| LLM 循环空转/重复失败 | 迭代预算 + 连续畸形检测 + 错误信息足够具体 |

---

## 12. M0 – M10 开发路线图

```
MVP 阶段（本仓库第一阶段）              后续扩展（按价值排序）
┌───────────────────────────┐  ┌──────────────────────────────────────────┐
│ M0 骨架                    │  │ M5 流式输出 / token 统计 / 上下文压缩      │
│ M1 数据 + 核心接口          │  │ M6 Git 集成（status/diff/commit 工具）    │
│ M2 六个 Tool               │  │ M7 MCP 支持（作为 MCP Host 桥接外部工具） │
│ M3 AgentLoop + 测试        │  │ M8 SubAgent（子任务并行/分层规划）        │
│ M4 真实 LLM + CLI 闭环     │  │ M9 RAG / 项目索引（语义检索）             │
│ M5 MVP 打磨                │  │ M10 Web UI + IDE 插件                    │
└───────────────────────────┘  └──────────────────────────────────────────┘
```

| 阶段 | 主题 | 说明 |
|---|---|---|
| M0–M5 | MVP（见 §11） | 单人闭环可用 |
| M5 | 流式与上下文管理 | SSE 流式输出；token 用量统计；长会话自动压缩历史（首轮只裁最旧 tool 结果） |
| M6 | Git 集成 | `git_status` / `git_diff` / `git_commit` 等工具；权限归 SHELL/WRITE 类；为"自动提交"做铺垫 |
| M7 | MCP | 作为 MCP Host：外部 MCP Server 的工具经桥接 AgentTool 注册进 Registry；复用现有 ToolExecutor 管线 |
| M8 | SubAgent | 复杂任务拆分子 Agent（分析/搜索/实现分派），主 Agent 汇总；引入任务 DAG |
| M9 | RAG / 项目索引 | 启动时构建代码索引（AST 符号 + 分块嵌入），`search` 升级为语义检索，减少盲目读取 |
| M10 | Web UI / IDE 插件 | 复用 `agent-core`（组合根替换为 Web 服务 / LSP）；IDE 插件复用同一 SPI |

**扩展不变式：** 所有后续能力都必须是"新实现 + 现有 SPI"，不允许改坏 core 的接口契约 —— 这是 `agent-core` 保持纯净的回报。

---

## 13. 关键架构决策（ADR）

| # | 决策 | 理由 |
|---|---|---|
| ADR-01 | 模块划分在建议 4 模块基础上增加 `agent-llm`，共 5 个 | core 不得依赖 HTTP/厂商；CLI 保持薄组合根 |
| ADR-02 | `agent-core` 不依赖 Spring / Spring AI / picocli；Spring Boot 仅作为 `agent-cli` 组合根 | 核心可独立单测、可被任意入口复用（原则 7） |
| ADR-03 | `LlmClient` 为自有 SPI；MVP 用 Spring AI 2.x 作 OpenAI-Compatible 适配层的传输实现，摩擦大则换 RestClient | 模型可替换是硬需求；适配层隔离变化（原则 4） |
| ADR-04 | 权限决策由 `ToolExecutor` 强制执行；Tool 只声明 `PermissionScope` | Permission 与 Tool 解耦（原则 6），Tool 保持无感知 |
| ADR-05 | 所有文件操作经 `WorkspaceAccess` 根目录围栏 | 原则 9 落地：Tool 永不直接触碰任意路径 |
| ADR-06 | 失败优先转为错误 `ToolResult` 回灌 LLM，而非抛异常；仅不可恢复错误才终止 | 让 LLM 自我纠正，配合预算兜底 |
| ADR-07 | Windows 默认 shell = `cmd.exe`，PowerShell 可配置切换 | 兼容性优先，最小可验证 |
| ADR-08 | 配置模型用纯 record + Jackson YAML 绑定，不用 `@ConfigurationProperties` | 与 ADR-02 一致，core 无 Spring 依赖 |
| ADR-09 | API Key 仅环境变量注入 + 日志脱敏过滤器 | 原则 8 落地，防泄露 |
| ADR-10 | `Agent` 门面 + `AgentLoop` 拆分；CLI/Web/测试共用 `Agent.run(task)` | 入口统一，便于未来替换组合根 |

---

## 14. 待确认的开放问题

1. **Spring AI 2.x vs 直连 RestClient**：ADR-03 建议先用 Spring AI（与既定技术栈一致），但预留直连方案。是否认可该取舍？（实现到 M4 时若适配成本高，将切换到直连并更新 ADR。）
2. **默认 Shell**：Windows 下默认 `cmd.exe`（兼容广）还是 `powershell`（能力全但慢、编码问题多）？当前设计默认 cmd。
3. **read_file 超限策略**：超 1 MB 直接报错（当前设计）还是截断前 N KB 并标记？当前设计选择报错，避免静默丢内容。
4. **权限默认值**：`write=ask, shell=ask` 是否满足预期？是否需要在 M4 后增加"会话内记忆批准结果"（同命令免重复询问）？
5. **交互体验细节**：是否需要在 REPL 中支持 `/tools`（查看工具）、`/permissions`（查看策略）等辅助命令？

---

## 15. 实现修订记录

> 以下记录 M0 + M1 实现与本文档设计基线的偏差。所有偏差均为实现层调整，
> 不改变架构总体结构与核心接口契约。

| # | 原设计 | 实际实现 | 原因 |
|---|---|---|---|
| R1 | 包名草案 `agent.core.*` / `agent.model.*` | `com.forgemind.core.*` / `com.forgemind.model.*`（含 tool/permission/fs/exception/loop/config 子包） | 以通用词 "agent" 开头的包名易与第三方冲突；与 groupId `com.forgemind` 保持一致 |
| R2 | `AgentResponse` 含 `finished` 字段 | 移除字段，由 `hasToolCalls()` / `isFinished()` 推导（`@JsonIgnore`） | 消除"有 toolCalls 却 finished=true"的矛盾状态；字段冗余无意义 |
| R3 | `ToolSchema` 含 `type` 字段 | `type` 恒为常量 `TYPE_OBJECT="object"`，不再作为 record 组件 | 避免每次构造传固定值；对外 JSON 由 LLM 适配层生成 |
| R4 | AgentLoop 持有 `PermissionManager` 等全部依赖 | AgentLoop 构造参数为 `(workingDirectory, LlmClient, ToolRegistry, ToolExecutor, AgentConfig)`；权限已内嵌于 ToolExecutor 链路；每次 `run()` 新建 AgentContext | AgentLoop 不直接做权限决策，避免重复持有；多任务复用同一 AgentLoop 时上下文隔离 |
| R5 | `ToolExecutor.execute(toolName, args)`（无调用 ID） | 保持该签名；ToolResult↔ToolCall 的 ID 关联由 AgentLoop 在回灌消息时完成（TOOL 消息 `tool_call_id` = call.id），`ToolResult.toolCallId` 字段保留但由调用方/工具自行填充 | 最小改动、职责清晰：关联发生在消息层（OpenAI 线格式要求），无需侵入执行器签名 |
| R6 | DefaultToolExecutor 流程图含"超时"步骤 | M1 实现"查找→校验→权限→执行"四步；per-tool 超时与 `ToolTimeoutException` 触发延至 M2（随 ShellTool 落地） | M1 无真实长耗时 Tool，超时框架无验证对象；避免为未来抽象（与本次指令"不扩大需求"一致） |
| R7 | 配置模型含 LlmConfig/PermissionConfig/ToolLimits | M1 仅实现 `AgentConfig(maxIterations)`，非法值抛 `ConfigException` | 其余配置随 M2/M4 里程碑加入 |
| R8 | Spring Boot parent 3.5.3 | 3.5.6 | 本地离线仓库（`D:\Program Files\maven-repo`）未缓存 3.5.3，3.5.6 可用 |

### M1 平台行为记录（Windows）

- 本机 Windows 11 已启用开发者模式：`Files.createSymbolicLink` 可创建符号链接，
  `WorkspaceAccess` 的符号链接逃逸用例**真实执行并通过**（未跳过）；
- Windows 下 `Path.startsWith` 大小写不敏感，围栏对大小写变体路径正确放行；
- 不同盘符的绝对路径被正确判定为越界。

---

## 16. M2 实现记录（6 个正式 Tool）

> M2 完成：6 个 AgentTool（list_files / read_file / write_file / edit_file / search / shell）、
> ShellProvider（cmd/powershell）、ToolLimits、shell 超时/进程树杀灭/输出截断。
> 实现与设计基线的一致性说明如下。

| # | 设计点 | 实现说明 |
|---|---|---|
| R9 | **安全补强（M1 遗留缺陷）**：`WorkspaceAccess` 原实现仅对"已存在的目标路径"做 realPath 校验 | M2 修复为 `isSafe()` 三层判定：词法检查 → 目标存在时 realPath 检查 → **目标不存在时检查"最近存在的祖先"的 realPath**（防经符号链接父目录写入不存在的文件而逃逸）。新增 2 个围栏测试（拒绝逃逸写入 / 放行站内链接写入） |
| R10 | ToolLimits 独立注入 | 按确认决策：M2 的 `ToolLimits`（含 `ShellType`）独立注入 `ToolContext`/`DefaultToolExecutor`（新增 5 参构造重载，原 4 参构造兼容保留），**未并入 `AgentConfig`**，M4 统一配置体系 |
| R11 | shell 结果呈现 | 按确认决策：stdout 与 stderr 合并进 `ToolResult.output`（stderr 用 `[stderr]` 分节标记）；`error` 仅描述非零退出码或超时；`exitCode`/`truncated` 保留；`success = exitCode==0 && !timedOut` |
| R12 | list_files 对"路径是文件" | 按确认决策：直接返回失败 `not a directory`，不列出单条 |
| R13 | shell 超时终止 | `ProcessRunner`：`waitFor(timeout)` 超时 → `destroyForcibly()` + Windows `taskkill /F /T /PID` 杀进程树；stdout/stderr 双 daemon 线程有界捕获（超限停止累积、继续 drain 防死锁，置 truncated） |
| R14 | shell 输出编码 | 按 UTF-8 解码；中文 Windows cmd 输出（GBK 代码页）可能乱码——已知问题，后续可用 `chcp 65001` 或按代码页解码改进 |
| R15 | search 上下文 | 按架构 §6.3：输出匹配行 + 行号 + ±2 行上下文（行内容截断 200 字符）；跳过 ignore 目录（来自 ToolLimits，可配） |
| R16 | Tool 直接调用 vs executor | required 参数校验仍在 executor（ArgumentValidator）层；Tool 直接调用缺参数时按"默认值/工作区根"处理（测试已注明） |

### M2 平台行为记录（Windows）

- shell 超时用例（800ms 超时 + `ping -n 4` 约 3s 命令）真实执行并通过：超时后进程树被杀、快速返回；
- `dir /b`、`for /L` 循环、`exit 42`、stderr 重定向等 cmd 命令均验证通过；
- PowerShell provider 冒烟测试通过（本机可用）。

---

## 17. M3 实现记录（AgentLoop 闭环 + Fake LLM）

> M3 完成：AgentLoop 完整错误处理（含连续畸形响应阈值）、ToolResult 完整回灌、
> FakeLlmClient 正式进入 agent-llm、完整闭环测试（agent-cli）。实现与设计基线的一致性说明如下。

| # | 设计点 | 实现说明 |
|---|---|---|
| R17 | **连续畸形响应**（架构 §5.3 矩阵落地） | 复用现有 `InvalidToolCallException`，阈值常量 3。畸形判定按"轮"：`chat` 返回 null、空响应（无 content 无 tool calls）、toolCalls 含空 id/空 name（含混合场景：**任一非法 → 整轮 invalid，任何工具都不执行**）。畸形 → USER 反馈消息 + 计数；合法响应归零；达 3 次抛 `InvalidToolCallException` → 被现有 catch 封装为 `AgentResult.failed`，不裸抛。**普通 Tool 执行失败不计入该计数**（属正常自纠流程） |
| R18 | **ToolResult 完整回灌**（要求 8） | `renderToolResult` 输出四行元数据 + 正文：`[tool: <name>]` / `[success: ...]` / `[exitCode: ...]` / `[truncated: ...]` + `<output>`（含 M2 的 `[stderr]` 分节）或 `ERROR: <error>`。`tool_call_id` 仍存于 `ChatMessage.toolCallId`，不拼入文本 |
| R19 | **FakeLlmClient 正式化** | 放入 `agent-llm` 生产源码 `com.forgemind.llm.fake.FakeLlmClient`；最小 API：`then / thenThrow / thenNull / calls / provider`；零网络依赖；脚本耗尽抛 `IllegalStateException` |
| R20 | **闭环测试位置** | 完整 Agent 闭环测试（真实 6 Tool + FakeLlmClient + AgentLoop）放 `agent-cli`（组合根，依赖全部模块，零主依赖新增）；agent-core 保留自身轻量 `StubLlmClient` 测试不变 |
| R21 | **agent-cli 测试日志绑定** | 实测 agent-cli 测试运行时缺 SLF4J 提供者（NOP 警告）→ 按"确认缺少才修改"原则补 `logback-classic`（test scope），并消除管道假性退出码 |
| R22 | **ToolTimeoutException 链路确认** | 继承 `ToolException → AgentException → RuntimeException`；`DefaultToolExecutor` 的 `catch (RuntimeException)` 已将其转为 `ToolResult.failure`（措辞含 "unexpectedly"，见已知问题）。**executor 未修改** |

### M3 行为记录

- `AgentLoop` 本轮新增逻辑全部由 `AgentLoopErrorHandlingTest`（15 用例，含"混合合法/非法 Tool Call 整轮无效"关键用例）与 `AgentLoopBudgetTest`（4 用例）锁定；
- 部分成果保留：预算耗尽/畸形终止时 `AgentResult.failed(partialAnswer, iterations, toolCallCount, error)`，已执行工具计数不丢失。

---

## 18. M4 实现记录（真实 OpenAI-Compatible LLM + CLI）

> M4 完成：OpenAiCompatibleLlmClient（JDK HttpClient，零第三方 HTTP 依赖）、LlmConfig、
> AgentConfig 合并 ToolLimits、Jackson YAML 配置加载、picocli CLI（单次任务 + REPL）。
> 与设计基线的一致性说明如下。

| # | 设计点 | 实现说明 |
|---|---|---|
| R23 | **HTTP 选型：JDK HttpClient**（ADR-03/§14 更新） | 不采用 Spring AI：仓库虽有 jar，但需要版本对齐与 spring-context 全家桶，且其抽象与自有 `LlmClient` SPI 重复；不采用 RestClient：单端点 POST 引入 spring-web 依赖链不值。JDK `java.net.http.HttpClient` 零依赖、离线可用、OpenAI wire 完全可控。无重试/连接池（MVP 不需要，失败抛 `LlmException` 由 AgentLoop 兜底） |
| R24 | **tools 注入方式** | `OpenAiCompatibleLlmClient(LlmConfig, List<AgentTool>)` 构造注入工具定义；`LlmClient.chat(List<ChatMessage>)` SPI 签名不变，不被 Provider 反向污染 |
| R25 | **AgentConfig 合并 ToolLimits** | `AgentConfig(int, ToolLimits)` + 兼容构造 `AgentConfig(int)`；`defaults()` 不变；AgentLoop 仍只读 `maxIterations`；ToolLimits 由装配层传 executor（单一配置来源） |
| R26 | **LlmConfig** | 放 agent-core/config；apiKey/model 允许为空（CLI 启动校验，错误信息不含 Key）；baseUrl 默认 `https://api.openai.com/v1`；connect/read 超时默认 10s/60s；withers 不可变 |
| R27 | **CLI = picocli 4.7.6**，无 Spring 容器 | `forgemind [--working-dir] [--yes] [--config] [task]` + `--help/--version`；单次任务 + REPL（exit 退出）；`--yes` → answerer 恒允许（不绕过 executor 权限链）；默认 `InteractivePermissionAnswerer`（`Allow? [y/N]`，与 REPL 共享 Scanner） |
| R28 | **arguments 解析失败 → 自纠** | `tool_calls.arguments` JSON 解析失败 → 空 Map，由 ToolExecutor 参数校验回灌 `missing required` → LLM 自纠，不中断循环 |
| R29 | **finish_reason** | 读取并记 debug 日志；行为仍以 tool_calls 有无为准（不据此分支） |
| R30 | **YAML 配置** | agent-cli 内绑定层（全包装类型）+ `ConfigLoader`（${ENV} 展开 → Jackson YAML → 领域配置，缺省用默认值）；未设置环境变量/非法 YAML/读取失败均明确报错；不使用 @ConfigurationProperties（ADR-08） |
| R31 | **测试基础设施** | `OpenAiCompatibleLlmClientTest` 用 JDK 内置 `com.sun.net.httpserver.HttpServer` 本地 mock（零依赖）；`ConfigLoaderTest` 通过注入 env Map 测试环境变量展开；CLI 通过注入 Fake LlmClient 测试 |

### M4 行为记录

- 端到端：`CliAssembly.buildAgent` + Fake LLM 跑通 read_file/write_file 全链路；`--yes` 与默认拒绝均验证；
- `config/example.yml` 仅含 `${FORGEMIND_API_KEY}`，无真实 Key；错误信息经测试断言不含 Key 值。

---

## 19. M5 实现记录（安全补强 + CLI 可执行化）

> M5 完成：日志脱敏、fat jar（forgemind.jar）、forgemind.cmd、Shell 中文输出修复、
> Mock E2E 沙箱。真实 LLM E2E 因环境无有效 API Key 未执行（见下）。

| # | 设计点 | 实现说明 |
|---|---|---|
| R32 | **日志脱敏** | `LogSanitizer`（sk-... / Bearer ... / 动态 FORGEMIND_API_KEY 值）+ `SanitizingConverter`（logback `%sanitize`）+ agent-cli `logback.xml`（console appender）；`LlmConfig.toString()` 覆盖为 `apiKey=***`（record 默认会输出明文，防御）；运行时日志绑定由 test scope 提升为 compile scope |
| R33 | **fat jar** | jar 插件写 `Main-Class: com.forgemind.cli.ForgemindCommand` manifest + shade 3.5.1（`finalName=forgemind`，无 transformer，保留原 manifest）。**shade `<transformer>` 配置在本环境（Maven 3.9.8）报 `Cannot find 'resource' in ManifestResourceTransformer`，3.6.0/3.5.1 均复现 → 改用 jar 插件 manifest 方案绕开**；项目无 ServiceLoader/SPI，无需 ServicesResourceTransformer |
| R34 | **forgemind.cmd** | 项目根启动脚本：`%~dp0` 定位 jar（带空格路径安全）、`%*` 透传参数、`java` 缺失或 jar 缺失给出明确错误、保留原始退出码；不依赖 bash/PowerShell、不改 PATH、不写死 JDK |
| R35 | **Shell 中文输出** | 曾尝试 `chcp 65001 &` 前缀：实测 Java ProcessBuilder 子进程下反而输出 U+FFFD 乱码（命令行参数已按启动代码页转换，chcp 无法修正）→ **回退前缀**；改为 `ProcessRunner` 解码升级：**严格 UTF-8，失败回退 `sun.jnu.encoding`（中文 Windows=Cp936/GBK）**，自动适配 cmd 的 ANSI 输出。中文 stdout/stderr 测试通过 |
| R36 | **打包后集成测试** | failsafe 3.5.4 + `PackageSmokeIT`（verify 阶段）：jar 存在、MANIFEST Main-Class、**真实执行** `java -jar forgemind.jar --version/--help`、forgemind.cmd 存在 |
| R37 | **Mock E2E** | `m5-e2e-workspace/`（示例文件沙箱）+ `E2eWorkspaceMockTest`：真实 WorkspaceAccess/ToolExecutor/6 Tool/AgentLoop/FakeLlmClient 在独立临时沙箱跑通 read/list/write/edit/search/shell，断言**真实文件系统状态** |
| R38 | **真实 LLM E2E** | **未执行**：当前环境无有效 FORGEMIND_API_KEY，不伪造结果；手工步骤已写入 README |

### M5 已知问题

1. **cmd 中文输出依赖环境代码页**：ProcessRunner 双解码（UTF-8 严格 → sun.jnu.encoding 回退）在本机（中文 Windows，cmd 输出 GBK）已验证正确；若将来需强制 UTF-8 输出，需在命令行参数层面处理（chcp 无效，已实测记录）。
2. shade `<transformer>` 配置在 Maven 3.9.8 下报错（R33），当前用 jar 插件 manifest 方案；若未来需要合并 META-INF/services（引入 ServiceLoader 后），需另寻方案并记录。
3. 真实 LLM 端到端（6 个示例任务）待有 Key 后按 README 步骤手工执行。

---

## 20. M6 实现记录（Git 感知 + Context/Tool Output 管理 + finish_reason + Coding Flow）

> M6 完成：git_status/git_diff（READ）、ToolResultRenderer（context 统一输出限制）、
> ContextCompactor（字符预算压缩）、AgentResponse.finishReason、Coding Flow 闭环测试。

| # | 设计点 | 为什么 / 方案 / 为什么不选其他 / 影响 | 测试 |
|---|---|---|---|
| R39 | **Git Tools**（git_status/git_diff，READ） | 需要 Git 感知而不把 git 当任意 shell 暴露。`GitProvider` 统一 `git -C <workspaceRoot>` 并**复用 ProcessRunner**（不重实现进程层）；git_diff 用 `--` 分隔符防 option injection，path 必须经 WorkspaceAccess 校验转相对路径；输出受 ToolLimits.outputLimit 限制。git_commit 暂缓（权限模型无独立 commit 权限）。影响：agent-cli standardTools 6→8 | GitStatusToolTest 6 + GitDiffToolTest 11（含非 git/越界/注入/大 diff/中文文件名） |
| R40 | **ToolResultRenderer** | Tool 输出应统一受 context 层限制，且**不修改原始 ToolResult**（CLI/UI 未来可拿完整结果）。规则：Tool 已 truncated=true 不重复截断；否则正文超 toolOutputLimit 截断 + `[output truncated: context output limit]`。替代方案（改 Tool 自身/改 ToolResult 结构）被否：破坏 M2 语义。影响：AgentLoop 渲染从私有方法迁移至 renderer | ToolResultRendererTest 8（含已截断/Unicode/原始不可变） |
| R41 | **ContextCompactor** | 长任务消息无界增长。粗字符预算（不引 tokenizer）；组划分：`ASSISTANT(tool_calls)+后续连续 TOOL` 原子组；SYSTEM 与最后组永删；tool_call_id 不孤裂；`maxChars<=0` 禁用；超预算剩受保护组时停止不抛。替代（滑动窗口/摘要）留 M7 | ContextCompactorTest 14（空列表/边界/原子删除/多轮/顺序） |
| R42 | **AgentResponse.finishReason** | finish_reason 需进入模型供 AgentLoop 感知。新增组件 + 保留 2 参兼容构造 + 工厂默认 null；OpenAI 客户端解析 stop/tool_calls/length/未知值（String 保留，不用 enum） | AgentResponseTest + JsonSerializationTest + OpenAiCompatibleLlmClientTest（stop/tool_calls/length/未知） |
| R43 | **AgentConfig 扩展** | contextMaxChars（默认 120k，0=禁用）+ toolOutputLimit（默认 64KB）；保留 `(int)` 与 `(int, ToolLimits)` 兼容构造；非法值校验。影响：旧测试零改动 | AgentConfigTest 4 |
| R44 | **finish_reason=length** | 不视为异常：有 tool_calls → 照常执行；无 tool_calls + content 非空 → completed；无 tool_calls + content 空 → 走既有畸形计数（3 次 → AgentResult.failed，不裸抛）。替代（自动续写请求）留 M7（Context Summary） | AgentLoopFinishReasonTest 4 + llm 层 5 |
| R45 | **Coding Flow 闭环** | status→read→edit→diff→final 真实多轮闭环（真实 git + 8 Tool + Fake LLM），断言真实文件修改、git diff 可见、tool_call_id 关联、消息序列；另验证 AgentLoop 真正调用 compaction 与 context 输出截断 | AgentLoopGitFlowTest 1 + AgentLoopContextCompactionTest 1 + AgentLoopToolOutputLimitTest 1 |

### M6 行为记录

- 集成测试确认：极小 contextMaxChars 下多轮大输出确实触发 `AgentLoop → compactIfNeeded → ContextCompactor`（末轮消息数 < 未压缩数，system 保留，tool_call_id 不孤裂）；
- 超大 Tool 输出经 `ToolResult → ToolResultRenderer → ChatMessage.tool` 截断（`[truncated: true]` + 截断标记），后续轮次正常完成；
- `mvn -o clean test / verify / package` 全部 BUILD SUCCESS（304 surefire + 5 failsafe = 309）。

---

*本文档为设计基线；进入编码阶段后，实现与设计的偏差需同步更新本文档并标注修订记录（见 §15–§20）。*
