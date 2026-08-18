# ForgeMind

一个类似 Claude Code / Codex 的 Coding Agent：理解任务、分析代码库、调用工具（读/写/改文件、搜索、执行命令）、运行测试并迭代直到完成任务。

> 当前进度：**M0–M5（安全脱敏 + CLI 可执行化 + Mock E2E）已完成**（245 个测试 + 5 个打包集成测试全绿），下一步为 M6+（Git/MCP/SubAgent 等）。

## Quick Start（M5，可复制执行）

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
