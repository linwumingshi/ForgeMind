# Agent Loop Efficiency & CLI Output Analysis

> 版本：v1.0（分析稿，未修改任何代码）
> 日期：2026-08-20
> 范围：只读代码分析 —— Agent Loop 无效迭代 / Token 浪费 / 开发日志治理 / 编码链路
> 依据：真实运行日志（`forgemind.cmd --working-dir C:\Users\linwe\Desktop\test-project --yes "查看当前项目有什么BUG并修复它"`，29 iterations / 28 toolCalls / success）+ 全链路源码逐行核对 + 本机环境实测（JDK 22 GraalVM）

---

## 0. 结论速览（TL;DR）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **Tool 失败诊断信息确实丢失，丢失点在 `ToolResultRenderer.java` L28-32**：失败分支只渲染 `ERROR: <error>`，把含 stderr 的 `output` 整体丢弃 | ProcessRunner 捕获完整 → ShellTool 合并进 output → Renderer 失败分支只留 error 一行 |
| 2 | **system prompt 完全没有环境信息**：无 OS、无 shell、无命令规则、无失败诊断指引 | `AgentLoop.systemPrompt()` L331-341 仅 3 段文本 |
| 3 | **没有任何重试/重复检测**：无相同命令检测、无连续失败计数、失败与正常迭代等额消耗预算 | AgentLoop 全文无相关逻辑 |
| 4 | **budget hint 在最后 1 轮才注入**（`maxIterations - 1`），收尾期无约束 | AgentLoop.java L133 |
| 5 | **完成判定缺失**：只要 LLM 返回 tool_calls 就执行，即使 content 已表达完成 | 循环唯一出口是 `!hasToolCalls()` |
| 6 | **`--verbose/--debug` 选项不存在**；所有内部 INFO 日志默认输出到控制台 | ForgemindCommand.java 无相关 Option；logback.xml root=INFO |
| 7 | **Logback 启动刷屏来源**：`<conversionRule>` 在 logback 1.5.x 被弃用告警 + status 自动打印 | logback.xml L5-6 |
| 8 | **乱码根因（实测确认）**：本机 JDK 22 下 `stdout.encoding=GBK` / `file.encoding=UTF-8`，ConsoleAppender 未指定 charset，输出字节与终端代码页错配 | `java -XshowSettings:properties` 实测 |
| 9 | 预计修复后同一任务迭代数：29 → **10~15 轮**（消除 shell 盲试 18 轮中的绝大部分） | 基于成功路径仅 9 轮推算 |

---

## 1. 本次真实运行复盘

运行命令：

```bash
.\forgemind.cmd --working-dir C:\Users\linwe\Desktop\test-project --yes "查看当前项目有什么BUG并修复它"
```

结果统计：

| 指标 | 值 | 说明 |
|---|---|---|
| 总 iterations | 29 | 预算 30，几乎耗尽 |
| 总 tool calls | 28 | 平均每轮 ~1 次 |
| subAgents | 0 | 未使用 |
| status | success | 第 29 轮 budget hint 注入后收敛 |
| **有效迭代** | **~9** | read/analyze(4) + 成功编译/运行/边界(4) + final(1) |
| **无效迭代** | **~18** | iteration 5-22 的 shell 连续盲试 |
| 失败 tool calls | ~14 | 全部为 shell 失败（javac/java 参数形态错误） |
| 重复失败 | ~14 | 同一意图（"编译并运行 OrderDemo"）反复换写法 |
| 最终成功路径 | `javac -d . demo\OrderDemo.java` + `java demo.OrderDemo` | 第 23-26 轮才找到 |

**无效重试序列（iteration 5-22 摘录）：**

```text
5  cd demo && javac OrderDemo.java && java OrderDemo
6  cd demo && javac OrderDemo.java; echo "exit= $ ?"          ← 开始换 shell 语法
7  pwd && ls -la && java -version                              ← 环境探测（无环境信息可依赖）
8  cmd /c "java -version"
9  cmd /c "cd demo && javac OrderDemo.java"
10 cmd /c "cd demo && java OrderDemo"
11 cmd /c "cd demo && java demo.OrderDemo 2>&1"
12 cmd /c "cd demo && java demo.OrderDemo"
...（直到 22）
```

**定性结论：问题不在"LLM 不会写 Java"，而在四件事同时缺失：**

1. **Tool 失败诊断信息** —— LLM 每轮只看到 `ERROR: exit code: 1`（Renderer 丢 stdout/stderr），无法知道"类路径错了"还是"没编译"，只能继续猜；
2. **环境认知** —— 系统提示没说这是 Windows/cmd，模型按 POSIX 习惯先试 `;`、`pwd`、`ls -la`；
3. **错误恢复策略** —— 没有"失败先分析 stderr、不要重复相似命令"的引导，也没有代码层的重复检测兜底；
4. **迭代预算管理** —— budget hint 到第 29 轮（`maxIterations-1`）才注入，18 轮盲试期间毫无约束。

---

## 2. Token 浪费根因（按优先级）

> 说明：当前日志不含 token usage 明细（streaming 通道的 usage 未打印），**无法精确计算浪费 token 数**。以下用"预计显著减少"定性，可精确量化的用迭代数。

| 优先级 | 根因 | 哪一层 | 为什么浪费 | 怎么修 | 预计减少迭代 |
|---|---|---|---|---|---|
| **P0-1** | 失败信息丢失 | `ToolResultRenderer` 失败分支 | LLM 看不到 stderr → 无法定位根因 → 换命令重试 | 失败分支渲染完整 output（含 `[stderr]` 节）+ command/workdir/OS/shell | 显著（消掉盲试主体） |
| **P0-2** | 环境认知缺失 | `AgentLoop.systemPrompt()` | 模型按 POSIX 习惯在 Windows 上失败 | 注入 OS / shell / wd / 命令规则 / 失败诊断指引 | 显著（首轮即可走对） |
| **P0-3** | 无重复失败控制 | `AgentLoop`（缺失） | 连续失败不被拦截，每轮全量 LLM round trip | 规范化 command hash + 连续失败计数 → 注入防重提示 | 中（收敛盲试） |
| **P0-4** | budget hint 太晚 | `AgentLoop` L133 | 29/30 才收尾，最后阶段无约束 | 阈值改 `maxIterations - 5`（或可配） | 小（防最后失控） |
| **P0-5** | 完成判定缺失 | `AgentLoop` 循环出口 | 任务完成后仍 list_files/git_status 验证 | content 含完成信号 + 验证类 tool → 提示直接 final | 1-3 轮 |
| P1-1 | 验证过度（prompt 引导） | `AgentLoop.systemPrompt()` | "验证链"无最小化约束 | prompt 明示"最小必要验证" | 1-3 轮 |
| P1-2 | 工具定义太简短 | `ShellTool.description()` | 未说明 cmd/Windows/路径规则 | 强化 description | 间接 |

---

## 3. Tool Failure 数据链（信息在哪一层丢失）

```
ProcessRunner          ShellTool            ToolResult          ToolResultRenderer          LLM
捕获：exitCode          合并：stdout+stderr   携带：output          失败分支：                 只收到：
  stdout  ✅            （[stderr] 分节）      error                 body = "ERROR: "+error  →  "ERROR: exit code: 1"
  stderr  ✅            error="exit code: N"  exitCode              【output 被丢弃】✗          （无 stderr / 无命令 /
  timedOut ✅           success=exit==0       truncated              L28-32                   无工作目录 / 无 OS）
  truncated ✅
```

### 3.1 ProcessRunner 实际捕获了什么 —— 全部捕获，零丢失

`ProcessRunner.java`（agent-tools）：

| 字段 | 来源 | 说明 |
|---|---|---|
| `exitCode` | `process.exitValue()`，超时置 -1 | L67 |
| `stdout` | `StreamGobbler` 有界捕获 → `out.content()` | L44/L68，UTF-8 严格解码 + `sun.jnu.encoding` 回退（L115-138） |
| `stderr` | 同上 → `err.content()` | L45/L68 |
| `timedOut` | `waitFor(timeout)` 失败 | L51-57 |
| `stdoutTruncated` / `stderrTruncated` | 超 `maxOutputBytes` 置位 | L141-176 |

**结论：ProcessRunner 层信息完整。** 唯一的潜在缺陷是解码策略"非 UTF-8 即整体回退 GBK"对**混合编码输出**（子进程里 GBK 中文路径 + UTF-8 中文内容并存）会整体错乱 —— 这正是乱码的一个来源（见 §9）。

### 3.2 ToolResult 实际保存了什么 —— stdout/stderr 合并进 output

`ToolResult.java`（agent-model）record 字段：`toolCallId / success / output / error / exitCode / truncated`。

**没有独立 stderr 字段**（设计如此，见 architecture.md R11）：`ShellTool.execute()`（L74-93）把 stdout + stderr 合并进 `output`，stderr 用 `[stderr]` 分节标记；`error` 仅描述 `exit code: N` 或 timeout。**stderr 此刻还在 output 里，没有丢。**

### 3.3 ToolResultRenderer 最终给 LLM 什么 —— 这里丢了

`ToolResultRenderer.java` L21-44：

```java
String body;
if (result.success()) {
    body = result.output() == null ? "(no output)" : result.output();
} else {
    body = "ERROR: " + (result.error() == null ? "unknown error" : result.error());  // ← L31
}
```

**丢失点确认：L31 失败分支只渲染 `error`（一行"exit code: 1"），`output`（含 stdout + [stderr] 节）被整体丢弃。**

另外两个缺失：
- 元数据只有 `[tool] [success] [exitCode] [truncated]`，**没有 command、working directory、OS、shell**（这些字段 ToolResult 里本就不存在，需要扩展）；
- `exitCode` 为 null 时输出 `null`。

### 3.4 用户控制台看到什么 —— 另一条更贫瘠的通道

`StreamingProgressRenderer.onToolResult`（agent-cli）只输出 `[success]` / `[failed]`（L50-54），**用户侧同样拿不到失败细节**。这条是观察层（不进 Context），与 LLM 通道互不影响，但体验上等于双重丢失。

### 3.5 答案汇总

| 问题 | 答案 |
|---|---|
| 1. ProcessRunner 捕获了什么？ | exitCode / stdout / stderr / timedOut / truncated，**全部捕获** |
| 2. ToolResult 保存了什么？ | success / output（stdout+stderr 合并）/ error（一行）/ exitCode / truncated |
| 3. Renderer 给 LLM 什么？ | 成功：output；**失败：仅 `ERROR: exit code: N`** |
| 4. 信息在哪层丢失？ | **`ToolResultRenderer.java` L31 失败分支丢弃 `output`**；另有元数据层缺失（无 command/wd/OS/shell） |

---

## 4. Agent Loop 重试 / 预算 / 停止机制

### 4.1 当前行为（已核对）

| 机制 | 现状 | 位置 |
|---|---|---|
| 迭代预算 | `maxIterations=30`（`AgentConfig.DEFAULT_MAX_ITERATIONS`） | AgentConfig L28 |
| budget hint | **`iterations >= maxIterations - 1` 才注入**（最后 1 轮） | AgentLoop L133-138 |
| 失败重试次数 | **不存在**：任何 Tool 失败都正常回灌、正常消耗 1 轮迭代 | AgentLoop 无相关计数 |
| 相同命令检测 | **不存在** | — |
| 连续失败检测 | **不存在**（仅"连续畸形响应"阈值=3，覆盖 null/空/空 id/空 name，不覆盖执行失败） | AgentLoop L54/L218 |
| 停止条件 | 唯一出口 `!response.hasToolCalls()`（有 content 无 tool_calls）→ completed | AgentLoop L165-182 |
| finish_reason | 仅记日志（L156-158）；`length` 且有 content → 续写；`tool_calls` 必然执行 | AgentLoop L171-181 |
| tool_choice | 恒为 `auto` | OpenAiCompatibleLlmClient L250/L274 |

### 4.2 关键结论

1. **一次 Tool 失败 = 一次完整迭代**（1 次 LLM 往返 + 执行）。没有"失败不消耗预算"的豁免，但也没有"失败扣双倍预算"的惩罚 —— 问题在于失败后 LLM 拿不到诊断信息，于是形成 18 轮盲试；
2. **budget hint 阈值 `maxIterations - 1` 太晚**：日志中 iteration 29 才出现 "budget nearly exhausted"，而盲试早在 iteration 5 就开始。建议改为 `maxIterations - 5`（剩余 ≤5 进入收尾模式），与用户建议一致；
3. **"重复失败检测"完全缺失**，值得加。用户建议的"规范化 command + hash + 连续失败次数"足够，**不需要 NLP**。

### 4.3 建议（供确认，未实现）

- **相同命令检测**：`command.trim().toLowerCase()` 去尾部 `2>&1`、统一 `cmd /c` 前缀后做 hash；连续 ≥2 次相同 hash 失败 → 注入提示"该命令已失败 N 次，请先分析 stderr 而非重复执行"；
- **相似命令检测（可选）**：对"cd X && ..."前缀归一后比较，覆盖率更高但误伤风险上升，建议一期只做相同命令检测；
- **连续失败护栏**：任一 tool 连续失败 ≥3 次 → 注入"停止重试，先 read_file 查看相关文件/搜索已有用法，或直接给出最终结论"。

---

## 5. Environment Awareness（当前状态：❌ 完全缺失）

`AgentLoop.systemPrompt()`（L331-341）**当前只包含**：

```text
You are a coding agent working in the directory: <workingDirectory>
You can use the following tools:
- <name>: <description>（每个工具一行）
When you need to inspect or modify the codebase, call the appropriate tool.
When the task is complete, reply with the final answer without tool calls.
```

逐一回答用户问题：

| 问题 | 现状 | 位置 |
|---|---|---|
| 1. 是否告诉 LLM OS？ | ❌ 无 | AgentLoop L331-341 |
| 2. 是否告诉 LLM shell？ | ❌ 无（ShellTool.description 也只写 "Execute a shell command in the workspace directory"） | ShellTool L47 |
| 3. 是否告诉 LLM working directory？ | ✅ 有（唯一注入的环境信息） | AgentLoop L333 |
| 4. 是否告诉 LLM 失败先分析 stderr？ | ❌ 无 | — |
| 5. 是否告诉 LLM 不要盲目重复相似命令？ | ❌ 无 | — |

**这就是 iteration 7-8 出现 `pwd && ls -la && java -version`、`cmd /c "java -version"` 的原因**：模型不知道自己在 Windows/cmd 上，先做环境探测。建议注入（方案见 §10 P0-2）。

---

## 6. 完成判定（为什么任务完成还在调用工具）

日志：iteration 27 `list_files`、iteration 28 `git_status`、iteration 29 final。

**代码事实：**

1. **没有"任务完成判定"**。AgentLoop 循环唯一出口是 LLM 响应不含 tool_calls（L165-182）。只要响应里带 tool_calls（OpenAI 常见行为：content 说"任务完成" + 顺手再调一个验证工具），就必然执行；
2. **finish_reason=tool_calls 强制继续**：R42 设计"行为以 tool_calls 有无为准，不据此分支"，所以 `finish_reason=tool_calls` 没有任何停止语义；
3. **系统提示的停止指令太弱**："When the task is complete, reply with the final answer without tool calls." 只是一句话，没有阻止"验证类调用"；
4. **没有"完成信号检测"**：不检查 content 中是否已声明完成、不区分"验证类工具"（list_files/git_status/git_diff 只读）与"实质工具"。

**建议（供确认）：**

- 方案 A（最简）：budget hint 提前到剩余 ≤5 轮 + 提示语强化"若已完成直接 final answer"；
- 方案 B（中等）：检测 `response.content` 含完成意图（如"done/finished/完成/已修复"）+ tool_calls 全部为只读验证类 → 注入"任务已完成，直接给出最终答案"；
- 方案 C（结构性）：AgentLoop 支持"LLM 在 content 中显式输出完成标记（如 `<done/>`）时，忽略随附的验证类 tool_calls"。**注意：A/B 为启发式，有误判风险，需测试覆盖；C 需要模型配合，可靠性最好。**

---

## 7. CLI 日志架构（现状 + 目标）

### 7.1 现状：两条输出通道混在一起

| 通道 | 输出者 | 内容 | 是否开发日志 |
|---|---|---|---|
| logback（root INFO） | AgentLoop / DefaultToolExecutor / ProcessRunner / OpenAiCompatibleLlmClient | `loop iteration 1/30`、`finish_reason=...`、`executing tool ...`、`tool ... -> success=true`、`permission denied`、`LLM API retry` | ✅ 是（用户看到的最吵部分） |
| System.out（直接打印） | StreamingProgressRenderer / ForgemindApp / InteractivePermissionAnswerer / ConfigReporter | `[tool: x] [success]`、`-- Final answer --`、`status/iterations`、`Allow? [y/N]`、`[OK]/[FAIL]` | ❌ 否（用户输出，但 `[tool]/[failed]` 太贫瘠） |

**关键事实：**

- `ForgemindCommand` **没有 `--verbose` / `--debug` 选项**（L38-70 全部 Option 核对过）；
- logback.xml：root `INFO` + 单 ConsoleAppender，pattern `%d{HH:mm:ss.SSS} %-5level %logger{36} - %sanitize%n`，**未指定 charset**（见 §9）；
- 因此默认运行时 `AgentLoop` 等所有 INFO 全量打屏。

### 7.2 目标分层（建议）

| 模式 | 触发 | 输出 |
|---|---|---|
| **默认（quiet）** | 无参数 | 仅用户输出：`Analyzing...` / `→ tool` / `[success]/[failed]` / `✓ Done` / 最终摘要（status/iterations） |
| **verbose** | `--verbose` | 默认输出 + logback INFO（AgentLoop/Executor/ProcessRunner 迭代与工具事件） |
| **debug** | `--debug` | verbose + logback DEBUG（token usage、finish_reason、retry、context 压缩、ToolResult 原文） |

落地方式：logback 默认级别降为 WARN（或只保留 ERROR），`--verbose` 动态升 INFO、`--debug` 升 DEBUG（`ch.qos.logback.classic.LoggerContext` 运行时调整）；System.out 通道不受 logback 影响，天然保持"用户层"。

---

## 8. Logback 问题

### 8.1 启动刷屏来源（已定位）

用户看到的：

```text
INFO in ch.qos.logback.classic.LoggerContext
INFO in DefaultJoranConfigurator
INFO in AppenderModelHandler
WARN ConversionRuleAction
```

来源：**`logback.xml` L5-6 的 `<conversionRule conversionWord="sanitize" converterClass="..."/>`**。logback 1.5.x（本地仓库存在 1.5.18/1.5.20/1.5.21/1.5.25，Spring Boot 3.5.6 BOM 管理）对 XML 中的 `conversionRule` 配置标记为 **deprecated**（推荐编程式注册 `Converter`），解析配置时打印该 WARN；logback 检测到 WARN status 后**自动把 status 输出到 System.out**（StatusPrinter 行为），于是整段启动刷屏。

### 8.2 deprecated warning 来源

同上：`ConversionRuleAction` 的弃用警告。两个解法：

1. 最小改动：在 logback.xml 加 `<statusListener class="ch.qos.logback.core.status.NopStatusListener"/>`（或启动参数 `-Dlogback.statusListenerClass=NopStatusListener`）静音 status —— **注意会吞掉未来真正的配置错误**；
2. 正解：改用编程式配置（`LogbackConfigurator` 在 `LoggerFactory.getILoggerFactory()` 上注册 `SanitizingConverter` + 设置 pattern），XML 保持精简，弃用告警消失且保留 status 可见性。

### 8.3 logger 级别现状

| Logger | 级别 | 建议 |
|---|---|---|
| root | INFO | 默认降到 WARN/ERROR（用户不关心）；`--verbose` 升 INFO、`--debug` 升 DEBUG |
| AgentLoop | INFO（loop iteration / finish_reason / tool 结果 / budget hint） | 全部降 DEBUG |
| DefaultToolExecutor | INFO（executing tool / permission denied） | 降 DEBUG（permission denied 可留 WARN） |
| ProcessRunner | WARN（timeout 杀进程） | 保留 WARN（真实异常） |
| OpenAiCompatibleLlmClient | INFO（API retry） | 降 DEBUG |
| ConsoleAppender | — | **显式 charset=UTF-8**（见 §9） |

---

## 9. 中文乱码（完整编码链路，实测证据）

### 9.1 本机环境实测（决定性证据）

```text
java version "22.0.1" (GraalVM)
file.encoding    = UTF-8     ← JEP 400（JDK 18+）默认
stdout.encoding  = GBK       ← ★ 控制台输出编码
stderr.encoding  = GBK
native.encoding  = GBK
sun.jnu.encoding = GBK       ← 文件路径 / 子进程参数编码
os.name          = Windows 11
```

**关键矛盾：`file.encoding=UTF-8` 但 `stdout.encoding=GBK`。** String 内部（UTF-16）正确，但写到控制台的字节按 GBK 编码。

### 9.2 全链路编码表

| 环节 | 实现 | 编码 | 依据 |
|---|---|---|---|
| 1. 输入（任务/权限回答） | `new Scanner(in, StandardCharsets.UTF_8)` | 固定 UTF-8 读 System.in | ForgemindCommand L142/L247 |
| 2. Java String | JVM 内部 | UTF-16（语义正确） | — |
| 3. 文件读写 | `Files.readString/writeString` + `project.build.sourceEncoding=UTF-8` | UTF-8 | UserConfigStore L76/L101；pom L26 |
| 4. LLM 响应 | Jackson `readTree`（HTTP body `ofString()` 默认 UTF-8） | UTF-8 | OpenAiCompatibleLlmClient L116/L373 |
| 5. 子进程 stdout/stderr | ProcessRunner：严格 UTF-8，失败回退 `sun.jnu.encoding`(GBK) | 双解码 | ProcessRunner L115-138 |
| 6. Logback ConsoleAppender | **未指定 charset → 跟随 System.out** | **GBK（实测）** | logback.xml L8-12 |
| 7. System.out 直出（StreamingProgressRenderer 等） | PrintStream(System.out) | **GBK（实测 stdout.encoding）** | ForgemindApp/渲染器 |
| 8. Windows 终端 | 取决于代码页 | 传统 cmd=GBK(936)；Windows Terminal 默认 UTF-8(65001) | 环境 |

### 9.3 乱码形态判定

用户看到 `鍟嗗搧鎬讳环`（= "商品总价" 的 **UTF-8 字节被 GBK 解码**），说明存在"**字节流是 UTF-8、解码方是 GBK**"的环节：

- **链路 A（最可能）**：子进程在 UTF-8 代码页终端下输出 UTF-8 中文（JDK 22 子程序 `stdout.encoding` 可能为 UTF-8）→ ProcessRunner 严格 UTF-8 解码**部分成功/失败**（输出混有 GBK 路径字节时整体回退）→ 回退分支 `new String(bytes, GBK)` 把 UTF-8 中文按 GBK 解成 `鍟嗗搧...`；
- **链路 B**：LLM 中文 → String 正确 → System.out（GBK 编码输出）→ 终端是 UTF-8 → 显示乱码（方向与 A 相反，形态不同）；
- **链路 C（输入侧）**：GBK cmd 中输入中文 → Scanner 按 UTF-8 解码 → 乱码（本项目 Scanner 固定 UTF-8 读入，与 GBK 终端输入不匹配）。

**结论：不是单点问题，是"输出编码不统一"的系统性问题。** 项目已有部分防御（ProcessRunner 双解码、ConfigReporter 用 `[OK]` 不用 ✓ 的注释 L82），但 ConsoleAppender / System.out / Scanner 三处编码没有对齐。

### 9.4 修复方向（供确认）

1. ConsoleAppender 显式 `charset="UTF-8"`；
2. `forgemind.cmd` 启动参数加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8`（JDK 19+ 支持 stdout/stderr.encoding），或文档要求终端 `chcp 65001`；
3. Scanner 读入与上述对齐（若强制终端 UTF-8，则 UTF-8 读入正确）；
4. ProcessRunner 保持双解码（已是合理兜底）；对"混合编码"的彻底解决需在解码失败时区分"纯 GBK"与"混合"，成本高，建议一期只做输出侧对齐。

> 附注：`Configuration saved to\:eepseek.com/v1]:` 这类片段，代码核对中 ConfigWizard 输出本身是干净的（L261-263 两个 println），推测为终端 `\r` 行覆盖或日志复制伪影，需复现确认，不列为代码 bug。

---

## 10. 推荐修复优先级（每项：文件 / 规模 / 测试 / 风险）

### P0-1 Tool failure diagnostics —— 失败信息必须完整回灌 LLM

| 项 | 内容 |
|---|---|
| 修改文件 | `ToolResultRenderer.java`（失败分支渲染 `output` 而非仅 error；元数据补 command/wd/OS/shell）；`ToolResult.java`（可选：新增 `command`/`workingDirectory` 字段，或由 AgentLoop 在渲染时注入上下文）；`ShellTool.java`（error 文案补充 `[stderr]` 引用）；`AgentLoop.java`（渲染前补充环境元数据） |
| 目标输出 | `Tool execution failed. Command: ... Working directory: ... Exit code: 1 stderr: ... stdout: ... Environment: OS=Windows Shell=cmd.exe` |
| 改动规模 | 小（2-4 文件，核心是 Renderer 一个分支） |
| 是否需要新增测试 | ✅ 必须：ToolResultRendererTest 新增"失败分支含 stdout/stderr/元数据"断言；ShellTool 失败用例回归 |
| 风险 | 低。注意 output 可能超长 → 复用既有 truncated 逻辑；**若直接暴露原始 output，需评估敏感信息**（脱敏只在 logback 层，LLM 通道无脱敏，命令输出一般安全） |

### P0-2 Environment awareness —— 把运行环境写进 system prompt

| 项 | 内容 |
|---|---|
| 修改文件 | `AgentLoop.java`（systemPrompt 注入：`OS=Windows / Shell=cmd.exe / WorkingDirectory=... / 命令规则 / 失败先看 stderr / 勿重复相似命令`）；`ShellTool.java`（description 强化：cmd 语义、`\` 路径、`javac -d .` 类示例） |
| 改动规模 | 小（2 文件） |
| 测试 | ✅ AgentLoopTest 断言 prompt 含环境块；ShellTool 测试补 description 快照 |
| 风险 | 低。prompt 变长但可控（<1KB）；对非 Windows 环境需做 `os.name` 分支（测试时用 Fake 环境信息注入） |

### P0-3 Retry / duplicate failure control —— 相同命令与连续失败护栏

| 项 | 内容 |
|---|---|
| 修改文件 | `AgentLoop.java`（新增：本轮内/跨轮相同规范化 command 计数；连续失败 ≥N 注入防重提示）；可抽 `CommandNormalizer`（小工具类，`trim/lowerCase/去尾部 2>&1/去 cmd /c 前缀`） |
| 改动规模 | 中（新逻辑 + 1 工具类） |
| 测试 | ✅ 新增 AgentLoopDuplicateCommandTest：相同命令 2 连败 → 提示注入；不同命令不误伤；阈值可配 |
| 风险 | 中。误伤"有意重试"场景 → 阈值保守（2-3 次）、仅对 shell tool 生效、提示而非阻止 |

### P0-4 Budget / completion —— 提前收尾 + 完成判定

| 项 | 内容 |
|---|---|
| 修改文件 | `AgentLoop.java`（budget hint 阈值 `maxIterations-1` → `maxIterations-5`，常量可配；可选：完成信号检测——content 含完成意图 + 仅只读验证类 tool_calls → 注入"直接 final"） |
| 改动规模 | 小-中 |
| 测试 | ✅ AgentLoopBudgetTest 增加"剩余 5 轮注入 hint"断言；完成判定测试（FakeLlmClient 脚本化：content=完成 + list_files → 期望提前结束） |
| 风险 | 中。完成判定是启发式，可能误判"任务未完成但用了完成措辞"→ 提示语保持"如果已完成"而非强制；阈值与判定均可配 |

### P1-1 默认 CLI 日志清理

| 项 | 内容 |
|---|---|
| 修改文件 | `agent-cli/src/main/resources/logback.xml`（root 默认 WARN/ERROR；AgentLoop/DefaultToolExecutor/LLM client logger 显式 WARN；仅 ERROR 上屏） |
| 规模 | 小 |
| 测试 | 无需新增（PackageSmokeIT 可加"默认运行无 INFO 输出"断言，可选） |
| 风险 | 低 |

### P1-2 Debug / verbose 模式

| 项 | 内容 |
|---|---|
| 修改文件 | `ForgemindCommand.java`（新增 `--verbose`/`--debug` Option）；新增 `LogLevelSwitcher`（agent-cli，运行时调整 LoggerContext 级别）或复用 CliAssembly |
| 规模 | 中 |
| 测试 | ✅ ForgemindCommand 参数解析测试；LogLevelSwitcher 单测（注入 LoggerContext） |
| 风险 | 低 |

### P1-3 Logback 启动噪声

| 项 | 内容 |
|---|---|
| 修改文件 | `logback.xml`（加 `<statusListener class="ch.qos.logback.core.status.NopStatusListener"/>`）或编程式 LogbackConfigurator 注册 `SanitizingConverter`（消除 conversionRule 弃用 WARN） |
| 规模 | 极小-小 |
| 测试 | PackageSmokeIT 断言启动输出无 logback status |
| 风险 | NopStatusListener 会吞真实配置错误 → 倾向编程式方案或保留 OnConsoleStatusListener 但接受 WARN |

### P1-4 Console 编码对齐

| 项 | 内容 |
|---|---|
| 修改文件 | `logback.xml`（ConsoleAppender `charset="UTF-8"`）；`forgemind.cmd`（`-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`，JDK 19+；`-Dfile.encoding=UTF-8` 兜底）；README（终端建议 chcp 65001） |
| 规模 | 小 |
| 测试 | ✅ 新增"中文输出在 UTF-8 控制台正确"集成断言（可在 PackageSmokeIT 或手工验收）；ProcessRunner 中文测试回归 |
| 风险 | 中。若用户终端仍是 GBK cmd，强切 UTF-8 会让"原本正常"的 GBK 终端变乱 → 需文档/探测（`System.console()` 代码页探测后自适应，或明确要求 UTF-8 终端） |

---

## 11. 验收标准（修复后同一任务的期望）

```bash
forgemind --working-dir C:\Users\linwe\Desktop\test-project --yes "查看当前项目有什么BUG并修复它"
```

### 11.1 迭代路径（期望形态）

```text
读取项目（list_files / read_file）
↓
分析代码（发现 BUG）
↓
修改（edit_file / write_file）
↓
最小必要验证（编译/运行 1-2 次，失败时从 stderr 直接定位）
↓
确认成功
↓
完成（final answer，不再附带 list_files/git_status 等收尾验证）
```

### 11.2 硬性指标

| 指标 | 当前 | 目标 |
|---|---|---|
| 总 iterations | 29 | **≤ 15**（理想 ≤ 12） |
| shell 盲试迭代 | ~18 | **≤ 3**（失败后第一轮就能定位） |
| status | success | success（不降级） |
| 失败信息 | `ERROR: exit code: 1` | 完整 Command/WD/Exit code/stderr/stdout/OS/Shell |
| 默认控制台 | AgentLoop/Executor/ProcessRunner INFO 刷屏 | **只有用户输出**（`Analyzing...` / `→ tool` / `✓ Done`） |
| `--debug` | 不存在 | 显示迭代/finish_reason/token/retry |
| 启动刷屏 | logback status 整段 | 无 |
| 中文输出 | 乱码（GBK/UTF-8 错配） | UTF-8 终端下正确 |

### 11.3 验收方式（必须实证）

1. 同一命令在 test-project 复跑，对照上表指标；
2. `--debug` 跑一次，确认开发日志完整但默认模式不可见；
3. 手工/集成测试覆盖：失败分支渲染、重复命令提示、budget hint 提前、UTF-8 输出。

---

## 附录 A：本次分析读取的源码清单

| 文件 | 路径 |
|---|---|
| AgentLoop | `agent-core/src/main/java/com/forgemind/core/loop/AgentLoop.java` |
| DefaultToolExecutor | `agent-core/src/main/java/com/forgemind/core/tool/DefaultToolExecutor.java` |
| ToolResultRenderer | `agent-core/src/main/java/com/forgemind/core/tool/ToolResultRenderer.java` |
| ToolResult | `agent-model/src/main/java/com/forgemind/model/ToolResult.java` |
| ChatMessage / AgentResponse / AgentResult | `agent-model/src/main/java/com/forgemind/model/` |
| ShellTool / ShellResult / CmdShellProvider / PowerShellShellProvider / ProcessRunner | `agent-tools/src/main/java/com/forgemind/tools/shell/` |
| GitStatusTool | `agent-tools/src/main/java/com/forgemind/tools/git/GitStatusTool.java` |
| AgentConfig / ToolLimits | `agent-core/src/main/java/com/forgemind/core/config/` |
| OpenAiCompatibleLlmClient | `agent-llm/src/main/java/com/forgemind/llm/openai/OpenAiCompatibleLlmClient.java` |
| ForgemindCommand / ForgemindApp / StreamingProgressRenderer / CliAssembly / InteractivePermissionAnswerer | `agent-cli/src/main/java/com/forgemind/cli/` |
| ConfigWizard / ConfigReporter / UserConfigStore / SanitizingConverter / LogSanitizer | `agent-cli/src/main/java/com/forgemind/cli/config/`、`.../logging/` |
| logback.xml | `agent-cli/src/main/resources/logback.xml` |
| forgemind.cmd / pom.xml / config/example.yml / docs/architecture.md | 项目根 |

## 附录 B：本机环境实测记录

```text
java version "22.0.1" (Oracle GraalVM)
file.encoding=UTF-8, stdout.encoding=GBK, stderr.encoding=GBK,
native.encoding=GBK, sun.jnu.encoding=GBK, os.name=Windows 11
logback-classic 本地仓库：1.2.3 / 1.4.14 / 1.5.18 / 1.5.20 / 1.5.21 / 1.5.25
```
