# ForgeMind P0/P1 实施计划（等待确认稿）

> 版本：v1.0（实施前确认稿，未改任何代码）
> 日期：2026-08-20
> 依据：`docs/agent-loop-efficiency-analysis.md`（只读分析）+ 本次测试侧现状核对
> 原则：最小改动、不重构架构、不引入新框架、只做四件事（P0-1 失败诊断 / P0-2 环境注入 / P0-3 重复失败护栏 / P1 默认日志治理）+ 两个附带项（logback 启动刷屏、编码对齐）

---

## 0. 现状核对结论（本次新增，非重复分析）

### 0.1 生产代码（上轮已核对，无变化）

| 类 | 关键事实 |
|---|---|
| `ToolResultRenderer` | 失败分支 L31 丢弃 output（含 `[stderr]` 节），只渲染 `ERROR: <error>` |
| `AgentLoop.systemPrompt()` | L331-341 仅 workdir + tools + 一句话停止指令 |
| `AgentLoop` | 无失败计数；budget hint 阈值 `maxIterations-1`（L133）；循环唯一出口 `!hasToolCalls()` |
| `ShellType` | 枚举 `CMD / POWERSHELL`（agent-core），可直接复用 |
| `ToolLimits` | `shellType` 字段可经 `AgentConfig.toolLimits()` 获取 |
| `ShellTool` | 失败时 output 已含 stdout + `[stderr]` 节（L74-83），error 仅 `exit code: N` |
| `logback.xml` | root=INFO；`<conversionRule conversionWord="sanitize">`；ConsoleAppender 无 charset |

### 0.2 测试设施（本次新核对）

| 文件 | 现状 | 对本计划的影响 |
|---|---|---|
| `ToolResultRendererTest`（9 用例） | 断言 `[tool]/[success]/[exitCode]/[truncated]` 元数据 + `ERROR: boom` | **元数据格式不能破坏**（AgentLoopTest L82-86 同样依赖）；`rendersFailureWithError` 需补断言而非改写 |
| `AgentLoopTest`（5 用例） | 用 `StubLlmClient`（`calls()` 返回每次调用消息快照）+ `EchoTool` + `FailingTool` | P0-2/P0-3/P0-4 全部可复用该模式，无需改测试框架 |
| `StubLlmClient` | 脚本化响应队列，纯 `chat()` 非流式 | 可断言每轮 system/user 消息内容 |
| `EchoTool` / `FailingTool` | testutil 最小工具 | P0-3 需新增一个**可控失败 shell 工具**（返回失败 ToolResult + command 参数） |
| `ShellToolTest`（10 用例） | 覆盖 exitCode/stderr/timeout/truncate/中文 | 不改，回归即可 |

**关键结论**：测试可以完全在现有 `StubLlmClient` + testutil 架构上落地，**不需要 mock 库、不需要改生产代码结构**。P0-2 的 OS 分支用「纯函数 `EnvironmentInfo.describe(osName, shellType, workdir)` 注入 osName」解决，生产代码零测试污染。

---

## 1. Step 1 — P0-1 Tool Failure Diagnostics（最小改 Renderer）

### 现状
`ToolResultRenderer.java` L28-32：失败分支 `body = "ERROR: " + error`，`output`（含 stdout + `[stderr]` 节）被丢弃。

### 改动（1 个文件）
`agent-core/.../core/tool/ToolResultRenderer.java` 失败分支改为：

```java
if (result.success()) {
    body = result.output() == null ? "(no output)" : result.output();
} else {
    // 失败分支：error 一行 + 完整 output（stdout + [stderr] 节）不得丢失
    StringBuilder fb = new StringBuilder("ERROR: ")
            .append(result.error() == null ? "unknown error" : result.error());
    String output = result.output();
    if (output != null && !output.isEmpty()) {
        fb.append("\n\nOutput:\n").append(output);
    } else {
        fb.append("\n\nOutput:\n(no output)");
    }
    body = fb.toString();
}
```

- **为什么只改 Renderer**：已确认 `ToolResult.output` 在 ShellTool 层就把 stdout/stderr 合并完整（L74-83），ToolResult record **不需要新增字段**（符合"先验证、不大改"约束）；
- 元数据头（`[tool]/[success]/[exitCode]/[truncated]`）**保持原样**，既有测试与 AgentLoop 消息格式零破坏；
- 非 shell 工具失败（output=null，如 `FailingTool`）输出 `(no output)`，不产生空节。

### 测试（1 个文件修改）
`ToolResultRendererTest.java`：
1. 保留既有 9 用例（`rendersFailureWithError` 的 `ERROR: boom` 断言仍成立）；
2. 新增 `failureKeepsOutputAndStderr`：构造 `new ToolResult(null, false, "line1\n[stderr]\nerr1", "exit code: 1", 1, false)` → 断言渲染含 `ERROR: exit code: 1`、`Output:`、`line1`、`[stderr]`、`err1`；
3. 新增 `failureWithNullOutputShowsPlaceholder`：`ToolResult.failure("boom")` → 断言含 `(no output)`；
4. 新增 `failureExitCodeAndTruncatedPreserved`：断言 `[exitCode: 1]`、`[truncated: true]` 保留（truncated 复用既有逻辑）。

---

## 2. Step 2 — P0-2 Environment Awareness

### 现状
`AgentLoop.systemPrompt()` 无任何环境信息 → 模型在 Windows 上按 POSIX 习惯盲试（iteration 7-8 的 `pwd`/`ls -la` 即因此产生）。

### 改动（2 个新文件 + 2 个修改文件）

**新增 `agent-core/.../core/env/EnvironmentInfo.java`**（纯函数，可测试，零依赖）：

```java
public final class EnvironmentInfo {
    // osName 由调用方传入（生产传 System.getProperty("os.name")，测试可注入假值）
    public static String describe(String osName, ShellType shellType, Path workingDirectory) {
        String os = osLabel(osName);          // win→Windows / mac→macOS / linux→Linux / 其他原样
        String shell = shellLabel(osName, shellType); // Windows+CMD→cmd.exe / Windows+PS→powershell.exe
                                                       // 非 Windows→sh（CMD/PS 在非 Windows 无 provider，按 sh 描述）
        // 返回：
        // Environment:
        // - OS: Windows
        // - Shell: cmd.exe
        // - Working directory: <abs path>
        //
        // Shell execution rules:
        // - Use commands compatible with the current shell.
        // - Do not assume Unix commands such as pwd, ls, grep, etc.
        // - When a shell command fails, inspect the returned stderr/output before trying another command.
        // - Do not blindly repeat the same or equivalent command.
        // - Prefer the smallest necessary verification command.
    }
    static String osLabel(String osName);     // package-private 可测
    static String shellLabel(String osName, ShellType shellType);
}
```

**新增 `agent-core/.../core/env/EnvironmentInfoTest.java`**：注入假 osName 覆盖 Windows / Linux / macOS / 未知 4 分支 + shell 名映射。**生产代码不读 System 属性，测试不 mock**。

**修改 `AgentLoop.java` `systemPrompt()`**：在现有文本前插入 `EnvironmentInfo.describe(System.getProperty("os.name",""), config.toolLimits().shellType(), workingDirectory) + "\n\n"`。增量约 8 行 / ~120 tokens，可控。

**修改 `ShellTool.java` `description()`**（强化工具定义，间接减少盲试）：在现有描述后追加一句 `"On Windows this runs via cmd.exe; use Windows-compatible syntax and backslash paths."` —— 仅在 `osName` 含 win 时追加（保持非 Windows 不受影响）。为最小化，此句由 ShellTool 构造时用 `System.getProperty("os.name")` 判断（现有类已构造于运行期，无测试污染；若测试断言 description 需注意——`ShellToolTest` 未断言 description，无影响）。

> 备选（若不想 ShellTool 依赖 os 判断）：description 保持不动，全部环境信息只放 systemPrompt。**默认采用备选**，降低改动面 —— description 强化的收益已被 Environment 块覆盖。

### 测试
`AgentLoopTest` 新增（本机即 Windows，直接断言）：
- `systemPromptContainsEnvironmentBlock`：断言 system 消息含 `Environment:`、`Windows`、`cmd.exe`、tempDir 绝对路径、`inspect the returned stderr`、`Do not blindly repeat`；
- `systemPromptListsRegisteredTools`（既有）继续通过。

---

## 3. Step 3 — P0-3 重复失败护栏（CommandFailureTracker）

### 现状
无任何失败计数：相同命令可无限重试，每轮消耗完整 LLM round trip。

### 改动（1 个新文件 + 1 个修改文件 + 1 个测试工具）

**新增 `agent-core/.../core/loop/CommandFailureTracker.java`**（小类，无框架）：

```java
public final class CommandFailureTracker {
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    static String normalize(String command);
    // normalize：trim → lowercase → 去尾部 "2>&1"（可重复）→ 去开头 "cmd /c "（含引号变体 "cmd /c \"..."）
    int recordFailure(String command);          // 返回连续失败次数（含本次）；只在 shell tool 失败时调用
    void recordSuccess(String command);         // 成功 → remove（计数归零）
    static String hintFor(int consecutive);     // 1→null；2→weak；>=3→strong
}
```

提示文案（固定常量，避免每轮重复生成）：
- weak：`This command has failed repeatedly. Analyze the previous stderr/output before retrying the same command.`
- strong：`The same command has failed multiple times. Do not retry it again without changing the underlying approach. Inspect the relevant files or use another diagnostic method.`

**修改 `AgentLoop.java`**：
- 新增字段 `private final CommandFailureTracker failureTracker = new CommandFailureTracker();`
- 工具执行循环内（L197-205），对 `call.name().equals("shell")`：
  - `result.success()` → `failureTracker.recordSuccess(command)`（command 从 `call.arguments().get("command")` 取，非 String 则忽略）；
  - 失败 → `int n = failureTracker.recordFailure(command); String hint = CommandFailureTracker.hintFor(n); if (hint != null) context.appendMessage(ChatMessage.user(hint));`
- **只提示、不抛异常、不终止**（允许真重试场景）；只针对 shell tool；不同命令 key 独立；成功后计数归零。

**新增测试工具 `agent-core/.../testutil/ControlledFailureTool.java`**：`name="shell"`，`schema` 要求 `command` 参数，`execute` 记录 command 并返回 `new ToolResult(null, false, "[stderr]\nboom: <command>", "exit code: 1", 1, false)`。权限 scope=SHELL（测试 answerer `req->true` 直接放行）。

### 测试（2 个新文件）
`CommandFailureTrackerTest.java`（纯单测）：
1. 相同命令第 1 次失败 → `hintFor(1)==null`；
2. 第 2 次 → weak 提示；第 3 次 → strong 提示；
3. `normalize`：`"CMD /C java Demo"`、`"cmd /c java demo 2>&1"`、`"  Java Demo 2>&1 "` → 同一 key；
4. 不同命令不互相污染；
5. `recordSuccess` 后重新失败从 1 计。

`AgentLoop` 集成测试（新增于 `AgentLoopTest` 或独立类 `AgentLoopRetryGuardTest`）：
- 注册 `ControlledFailureTool`；`StubLlmClient` 脚本：LLM 第 1 轮返回 `shell(java demo.OrderDemo)` → 第 2 轮返回相同命令 → 第 3 轮 final；
- 断言：第 1 轮 TOOL 消息后**无**防重提示；第 2 轮上下文（`calls().get(2)`）含 weak 提示；第 3 轮含 strong 提示；
- 成功清零场景：脚本 shell 失败 → 成功 → 再失败 → 断言第二次失败后无提示（计数已归零）。

---

## 4. Step 4 — P0-4 提前 budget hint

### 现状
`AgentLoop.java` L133：`iterations >= config.maxIterations() - 1`（最后 1 轮才注入）。

### 改动（1 个文件）
```java
int remaining = config.maxIterations() - iterations;
if (!budgetHintInjected && remaining <= 5) { ... }
```
- 替换现有 `BUDGET_HINT_PROMPT` 文案为（用户给定，短）：
  `Iteration budget is nearly exhausted. Stop exploratory retries. Complete the task using the minimum necessary actions and provide the final answer.`
- `budgetHintInjected` 标志保留 → **只注入一次**，不重复增加 token；
- `maxIterations=2` 时：iteration 1 的 remaining=1 ≤ 5 → 首轮即注入（小预算尽早收尾，行为合理）；`remaining` 非负，无负数逻辑问题。

### 测试（新增于 `AgentLoopTest`）
- `maxIterations=7`（第 1 轮 remaining=6）→ 不触发：断言第 2 轮消息无 hint；
- `maxIterations=6`（第 1 轮 remaining=5）→ 触发：断言第 2 轮消息含 `Iteration budget is nearly exhausted`；
- `maxIterations=2` → 首轮触发（行为正确）；
- 触发后不再重复注入（`budgetHintInjected` 断言，可用脚本多轮验证）。

---

## 5. Step 5 — P1 默认 CLI 日志治理

### 现状
root=INFO，AgentLoop/DefaultToolExecutor/LLM client 的迭代/执行/finish_reason/retry 全量刷屏。

### 改动（2 个文件，代码层 + 配置层双保险）

**代码层（开发日志 `log.info` → `log.debug`）**：

| 文件 | 行 | 原 | 改 | 理由 |
|---|---|---|---|---|
| `AgentLoop.java` | L134 | info | debug | budget hint 注入（开发观测） |
| `AgentLoop.java` | L139 | info | debug | `loop iteration {}/{}`（最吵） |
| `AgentLoop.java` | L157 | info | debug | finish_reason |
| `AgentLoop.java` | L201 | info | debug | `tool '{}' -> success=...`（最吵） |
| `DefaultToolExecutor.java` | L83 | info | debug | `executing tool '{}' args={}` |
| `DefaultToolExecutor.java` | L79 | info | **warn** | permission denied（用户需要知道） |
| `OpenAiCompatibleLlmClient.java` | L109/L203/L221 | info | debug | API retry（开发观测） |

**保留 WARN/ERROR 不动**：AgentLoop cancelled/terminated/invalid response、ProcessRunner timeout、permission denied 等真实异常。

**配置层 `logback.xml`**：`<root level="WARN">`（用户默认只见 WARN/ERROR；System.out 用户通道不受影响）。

- 为什么两层都做：代码层标记"哪些是开发日志"（未来 `--verbose` 升 DEBUG 即可见），配置层兜底（未来 root 回 INFO 也不会恢复噪音）。

### 测试
- 既有测试不受影响（JUnit 输出不依赖级别）；
- `PackageSmokeIT`（agent-cli 已有）不新增断言，**手工验收**：默认运行控制台无 `INFO com.forgemind.core.loop`。

---

## 6. Step 6 — Logback 启动刷屏

### 现状
`<conversionRule conversionWord="sanitize" converterClass="...SanitizingConverter"/>` 在 logback 1.5.x 触发弃用 WARN → StatusPrinter 整段刷屏。

### 方案（按优先级，实施时先试 1）
1. **编程式注册 converter（首选，无副作用）**：在 `ForgemindCommand.main()` 或 `ForgemindApp` 静态初始化处：
   ```java
   LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
   lc.putObject("conversionRule_sanitize", SanitizingConverter.class);
   ```
   同时从 logback.xml **删除** `<conversionRule>` 标签。⚠️ 需实施时核对 logback-core 1.5.x `ConversionRuleAction` 的 putObject **key 格式与 value 类型**（读本地 `~/.m2/repository/ch/qos/logback/logback-core/1.5.x` 字节码确认），若 key 格式不符则回退方案 2。
2. **NopStatusListener（备选）**：logback.xml 加 `<statusListener class="ch.qos.logback.core.status.NopStatusListener"/>`。**副作用：吞掉未来所有配置错误 status**。若采用，同时保留启动参数覆盖通道（`-Dlogback.statusListenerClass=OnConsoleStatusListener` 可临时回显），并在 README 注明。

### 测试
- 手工：默认启动无 `INFO in ch.qos.logback...` / `WARN ConversionRuleAction`；
- 若走方案 1，`SanitizingConverter` 脱敏行为由既有测试覆盖（`LogSanitizer` 逻辑不变）。

---

## 7. Step 7 — Windows UTF-8 输出对齐（最小安全）

### 现状（已实测）
JDK 22：`file.encoding=UTF-8`、`stdout.encoding=GBK`、`stderr.encoding=GBK`、`sun.jnu.encoding=GBK` → System.out / ConsoleAppender 输出字节与终端代码页错配 → 中文乱码。

### 改动（2 个文件，最小且可回退）

**`logback.xml`**：ConsoleAppender 加 `charset="UTF-8"`（显式化，消除"跟随 System.out=GBK"的不确定性）。

**`forgemind.cmd`**：启动前 `chcp 65001 >nul`（把 cmd 会话代码页切到 UTF-8）+ Java 参数：

```bat
chcp 65001 >nul
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" %*
```

- JDK 19+ 支持 `stdout.encoding/stderr.encoding`（本机 JDK 22 生效）；JDK 17 下这些属性被忽略但无副作用（`file.encoding` 兜底）；
- `chcp 65001` + UTF-8 输出 → 终端输入输出两端一致，Scanner（UTF-8 读入）匹配；
- **ProcessRunner 双解码（UTF-8 严格 + sun.jnu.encoding 回退）不动**；
- 风险与回退：GBK 传统终端下 `chcp 65001` 会切换代码页（字体需支持），若个别环境异常，删掉该行 + 参数即可回退。

### 测试
- `ShellToolTest` 中文用例（`chineseStdoutIsDecodedAsUtf8` / `chineseStderrIsCaptured`）回归；
- 手工：真实任务中文输出正常。

---

## 8. Step 8 — 全量验证与真实运行验收

1. `mvn -q test`（全模块）—— 所有既有 + 新增测试通过；
2. `mvn -q package`（含 failsafe 集成测试）；
3. 真实运行（验收标准，见下）：
   ```bash
   .\forgemind.cmd --working-dir C:\Users\linwe\Desktop\test-project --yes "查看当前项目有什么BUG并修复它"
   ```
4. 对照验收清单（用户 §十）逐项实证，重点：
   - iterations ≤ 15（理想 ≤ 12）；shell 失败后首轮可见真实 stderr；
   - 无 `pwd`/`ls`/`cmd /c` 环境探测盲试；相同失败命令不无限重复；
   - 剩余 5 轮进入收尾；默认控制台无 AgentLoop INFO；无 logback 启动刷屏；中文正常。
5. **若仍 >15 iterations**：不宣布完成，对照本次日志逐条归因（失败轮次 / 命令 / 提示是否生效），输出下一轮最小修复方案。

---

## 9. 风险与边界清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| P0-1 渲染格式变化影响既有断言 | 低 | 元数据头原样保留；既有 9 用例 + AgentLoopTest 消息断言回归 |
| P0-2 prompt 增量 | 低 | 约 120 tokens，远小于每次盲试开销 |
| P0-3 误伤合法重试 | 中 | 只提示不阻断；阈值 2/3；仅 shell tool；成功后清零 |
| P0-4 小预算提前收尾过激 | 低 | 只提示不强制；maxIterations=2 时首轮提示行为已定义 |
| P1 root=WARN 掩盖需要的信息 | 低 | permission denied 升 WARN 保留；用户输出走 System.out 不受影响 |
| Step 6 方案 1 的 putObject API 不确定性 | 中 | 实施时读本地 logback-core 字节码确认；不符则回退 NopStatusListener（含回显通道） |
| Step 7 编码改动影响 GBK 终端 | 中 | chcp + JVM 参数两端对齐；可单行回退；ProcessRunner 不动 |

## 10. 明确的"不做"清单（对齐用户约束）

- ❌ 不引入 Spring AI / 新 Provider / 模型配置改动
- ❌ 不重写 AgentLoop / 不重构 ToolResult record / 不改 Tool API / 不改 CLI 参数
- ❌ 不做完成语义识别 / `<done/>` / NLP 相似命令 / token usage 统计
- ❌ 不删任何现有日志（只降级级别）
- ❌ 不实现 `--verbose/--debug`（下一阶段）

---

## 11. 待确认项（请拍板后开工）

1. **P0-1 失败输出格式**：采用「保留元数据头 + `ERROR: <error>` + `Output:` 节」最小兼容方案（推荐），还是改成用户建议的纯文本 `Tool execution failed.` 风格（会破坏既有元数据断言，需同步改 2 处测试）？
2. **P0-2 ShellTool.description**：默认不追加（环境信息只放 systemPrompt），是否同意？
3. **Step 6**：先试编程式注册（无副作用），失败才退 NopStatusListener —— 是否同意该顺序？
4. **Step 7**：`forgemind.cmd` 加 `chcp 65001` + UTF-8 JVM 参数 —— 是否接受（GBK 终端用户需改字体，可回退）？
