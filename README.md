# ForgeMind

一个类似 Claude Code / Codex 的 Coding Agent：理解任务、分析代码库、调用工具（读/写/改文件、搜索、执行命令）、运行测试并迭代直到完成任务。

> 当前进度：**M0–M8（Streaming 实时输出 + Tool 增量展示 + 取消边界）已完成**（453 个测试 + 5 个打包集成测试全绿），下一步为 M9+（MCP/SubAgent 等）。

## Coding Agent 完整工作流（M7 闭环验证）

> `git_status → read_file → edit_file → git_diff → git_commit → git_status → final`
> 已由 `AgentLoopGitCommitFlowTest` 以真实 Git + 9 Tool 闭环验证：真实文件修改、
> commit 创建（git log 可见）、最终 status clean、tool_call_id 完整；COMMIT DENY 时
> failure 回灌且 Agent 自纠不崩溃。

```powershell
.\forgemind.cmd --working-dir D:\workspace --yes "检查 Git 修改，修复 src\Bug.java 的 bug，运行测试，查看 diff 并 git commit"
```

## 模块结构（M8 更新）

| 模块 | 职责 |
|---|---|
| `agent-model` | 纯数据模型：消息、Tool Call/Result、Schema、AgentResponse（含 finishReason）、**ContextSummary**（无业务依赖，仅 Jackson） |
| `agent-core` | 核心编排：Agent / AgentLoop（畸形阈值/完整回灌/压缩+Summary/续写/**Streaming 通道分派**/**取消边界**）/ Tool SPI / Permission（READ/WRITE/SHELL/**COMMIT**）/ WorkspaceAccess / 异常 / ToolLimits / LlmConfig / **LlmStreamClient SPI / ProgressListener 观察层** / ToolResultRenderer / ContextCompactor / TokenEstimator / DeterministicContextSummaryExtractor / RetryPolicy / Sleeper（仅依赖 model + slf4j，无 Spring） |
| `agent-llm` | FakeLlmClient + OpenAiCompatibleLlmClient（JDK HttpClient；finish_reason；**SSE 流式解析 + 流式 Tool Call 累积**；指数退避重试） |
| `agent-tools` | 9 个 AgentTool：list_files / read_file / write_file / edit_file / search / shell / **git_status / git_diff / git_commit**（GitProvider 复用 ProcessRunner，COMMIT 独立权限） |
| `agent-cli` | picocli CLI + **StreamingProgressRenderer 增量输出** + 日志脱敏 + YAML 配置 + shade fat jar + 闭环/集成测试 |

## Streaming（M8）

- **传输层变化，领域逻辑不变**：`LlmStreamClient extends LlmClient`；AgentLoop 检测到流式能力自动走 `stream()`，否则回退 `chat()` —— 两种模式完全兼容，`chat()` 语义不退化。
- **SSE 管线**：`OpenAiSseParser`（SSE→data）→ `OpenAiStreamAccumulator`（data→增量+完整响应）→ `StreamToolCallAccumulator`（tool_call 分片累积、arguments 一次性解析）。
- **实时增量输出**：CLI 经 `StreamingProgressRenderer` 逐字符打印文本 delta（每次 flush），Tool 调用/结果显示为 `[tool: name] [success]` / `[failed]`。
- **delta 不进 Context**：AgentContext 只存完整 AssistantMessage（完整 content + 完整 tool_calls）与 ToolResult，tool_call_id 严格配对；增量仅供展示。
- **流式 Retry**：仅 body 消费前重试（IO 失败 + 429/500/502/503/504）；2xx 且 SSE 已开始绝不重试。
- **取消**：线程中断 → AgentLoop 在循环边界返回 `failed("cancelled")`；运行中的 Tool 不被打断（自然完成），后续不再调用 LLM。CLI 表现为 `[not finished] cancelled`。
- 安全链不变：流式完整 ToolCall 仍走 `AgentLoop → ToolExecutor → PermissionManager → WorkspaceAccess → AgentTool`；DENY 经流式通道 failure 回灌自纠。

## Context 管理（M7）

- **Token Budget**：`contextMaxTokens`（默认 100k，0=禁用回退字符预算）+ `contextReserveTokens`（8k）；`ApproximateTokenEstimator`（ASCII≈4c/token、CJK≈1.5c/token，近似非计费）。
- **Context Summary**：压缩旧消息组时由 `DeterministicContextSummaryExtractor`（不调 LLM）提取 task/modifiedFiles/commands/testResults，以 SYSTEM `[CONTEXT SUMMARY]` 注入；原子组删除，tool_call_id 不孤裂。
- **length 自动续写**：`finish_reason=length` → 注入 continuation（`maxContinuationAttempts` 默认 2）；length+tool_calls 先执行工具。
- **HTTP Retry**：429/500/502/503/504 指数退避重试（500ms→5s ×2，jitter 可关）；400/401/403/404/422 立即失败；错误不泄漏 API Key。

## Git Workflow 与权限模型

| 权限 | 默认 | 工具 |
|---|---|---|
| READ | ALLOW | read/list/search/git_status/git_diff |
| WRITE | ASK | write_file/edit_file |
| SHELL | ASK | shell |
| **COMMIT** | **ASK** | **git_commit**（`git add -A` + `git commit -m`，message 为独立进程参数，注入安全） |

`--yes` 仅表示 Answerer 恒允许，**仍必经 ToolExecutor → PermissionManager → WorkspaceAccess → GitProvider → ProcessRunner**。

## 测试（453 surefire + 5 failsafe，全绿）

- agent-model：36 · agent-core：111（含 TokenEstimator 10 / RetryPolicy 6 / Summary 提取 7）
- agent-llm：97（含 SSE 解析 14 / 流式累积 27 / 流式客户端 15 / Fake 流式 5）
- agent-tools：102（含 git_status 6 / git_diff 11 / git_commit 14）
- agent-cli：107（含 Streaming 集成 / Cancellation 3 / Renderer 4 / 流式 CLI 2 / Continuation 8 / GitCommitFlow 2 / FinishReason 4）

## Quick Start（可复制执行）

### 1. 设置 API Key（绝不写入源码/配置/Git）

```powershell
$env:FORGEMIND_API_KEY = "sk-..."   # 你的 OpenAI-Compatible Key
```

### 2. 打包

```powershell
mvn package        # 生成 agent-cli\target\forgemind.jar
```

### 3. 运行

```powershell
# Windows 启动脚本（项目根）
.\forgemind.cmd                            # 交互 REPL（输入 exit 退出）
.\forgemind.cmd --config config\example.yml "分析当前项目"
.\forgemind.cmd --working-dir D:\workspace --yes "列出项目结构"
.\forgemind.cmd --yes "读取 README.md 并总结架构"

# 或直接 java -jar
java -jar agent-cli\target\forgemind.jar --help
java -jar agent-cli\target\forgemind.jar "单次任务"
```

## 真实 LLM E2E（manual，6 个示例任务）

> 注意：本仓库所有自动化测试均使用 Fake LLM / 本地 Mock，**不依赖真实 API Key**。
> 真实 E2E 需你自己提供 Key，在独立沙箱 `m5-e2e-workspace/` 中执行
> （WorkspaceAccess 保证所有文件操作被限制在该目录内）：

```powershell
$env:FORGEMIND_API_KEY = "sk-..."
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "读取 README.md，并总结项目当前架构"
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "列出当前项目目录结构，并说明主要模块"
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "读取 src\Test.java，并解释其作用"
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "创建一个测试文件，然后读取它"
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "修改测试文件中的指定文本"
.\forgemind.cmd --config config\example.yml --working-dir m5-e2e-workspace --yes "执行一个安全的 shell 命令，例如 dir"
```

每次运行末尾输出 `iterations` / `toolCalls` 统计。
**当前仓库状态：真实 LLM E2E 未执行（本环境未提供 API Key）；Mock E2E 已通过（`E2eWorkspaceMockTest` 4 用例）。**

## 模块结构

| 模块 | 职责 |
|---|---|
| `agent-model` | 纯数据模型：消息、Tool Call/Result、Schema（无业务依赖，仅 Jackson） |
| `agent-core` | 核心编排：Agent / AgentLoop / Tool SPI / Permission / WorkspaceAccess / 异常 / ToolLimits / LlmConfig（仅依赖 model + slf4j，无 Spring） |
| `agent-llm` | FakeLlmClient（测试）+ OpenAiCompatibleLlmClient（JDK HttpClient，OpenAI/DeepSeek 兼容） |
| `agent-tools` | 6 个 AgentTool 实现：list_files / read_file / write_file / edit_file / search / shell（含 cmd/powershell provider，UTF-8/GBK 双解码） |
| `agent-cli` | picocli CLI（单次任务 + REPL）+ 日志脱敏 + YAML 配置 + shade fat jar + 打包集成测试 |

## 配置（config/example.yml）

```yaml
llm:
  baseUrl: https://api.deepseek.com/v1   # 或 OpenAI 官方
  apiKey: ${FORGEMIND_API_KEY}           # 仅环境变量注入
  model: deepseek-chat
```

## 构建

要求：JDK 17+（编译目标 17）、Maven 3.9+。本机离线构建方式（本地仓库
`D:\Program Files\maven-repo`，需用 PowerShell 调用完整路径）：

```powershell
& 'D:\Program Files\Maven\bin\mvn.cmd' -o clean test
& 'D:\Program Files\Maven\bin\mvn.cmd' -o verify
& 'D:\Program Files\Maven\bin\mvn.cmd' -o package
```

联网环境直接 `mvn clean test` / `mvn verify` / `mvn package` 即可。

## 测试（245 单元/集成测试 + 5 打包集成测试，全绿）

- agent-model：26 个（数据模型、防御性拷贝、Jackson 序列化/反序列化）
- agent-core：56 个（WorkspaceAccess 17、ToolExecutor 12、Permission 9、Registry 6、
  AgentLoop 5、ToolLimits 3、LlmConfig 3、DefaultAgent 1）
- agent-tools：71 个（list_files 16、read_file 11、search 10、edit_file 9、
  write_file 7、shell 10（含中文 stdout/stderr）、executor 集成 8）
- agent-llm：31 个（FakeLlmClient 7 + OpenAiCompatibleLlmClient 24）
- agent-cli：61 个（闭环 26 + ConfigLoader 8 + 命令 5 + 装配 5 + 权限应答 6 +
  日志脱敏 7 + Mock E2E 4）
- 打包集成测试（failsafe）：5 个（jar 存在 / Main-Class / `java -jar --version` /
  `java -jar --help` / forgemind.cmd 存在）
  AgentLoop 5、ToolLimits 3、LlmConfig 3、DefaultAgent 1）
- agent-tools：69 个（list_files 16、read_file 11、search 10、edit_file 9、
  write_file 7、shell 8、executor 集成 8）
- agent-llm：31 个（FakeLlmClient 7 + OpenAiCompatibleLlmClient 24：请求/解析/401/429/500/
  超时/空 choices/非法 arguments/Authorization/tools schema）
- agent-cli：50 个（完整闭环 26 + ConfigLoader 8 + 命令 5 + 装配 5 + 权限应答 6）
- 覆盖重点：`../` 逃逸、绝对/跨盘符路径、符号链接逃逸（含经链接父目录写新文件）、
  根目录写入拒绝、二进制/超大文件、edit 多匹配且失败不改原文件、search ignore 目录与结果上限、
  shell exitCode/stdout/stderr/timeout（含进程树杀灭）/输出截断/工作目录、
  LLM HTTP 错误/超时/畸形回灌自纠、CLI --yes/默认拒绝/单次任务/REPL

## 开发路线

M0 骨架 → M1 数据模型+核心接口 → M2 六个 Tool → M3 AgentLoop 完整错误处理 →
M4 真实 LLM + CLI → M5 打磨 → M6+ Git / MCP / SubAgent / RAG / Web UI / IDE Plugin

详见 [`docs/architecture.md`](docs/architecture.md)。
