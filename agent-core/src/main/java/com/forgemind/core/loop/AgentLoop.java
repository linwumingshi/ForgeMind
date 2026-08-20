package com.forgemind.core.loop;

import com.forgemind.core.config.AgentConfig;
import com.forgemind.core.context.AgentContext;
import com.forgemind.core.context.DeterministicContextSummaryExtractor;
import com.forgemind.core.context.TokenEstimator;
import com.forgemind.core.env.EnvironmentInfo;
import com.forgemind.core.exception.AgentException;
import com.forgemind.core.exception.InvalidToolCallException;
import com.forgemind.core.exception.LlmException;
import com.forgemind.core.exception.MaxIterationsExceededException;
import com.forgemind.core.llm.LlmClient;
import com.forgemind.core.llm.LlmStreamClient;
import com.forgemind.core.llm.LlmStreamListener;
import com.forgemind.core.llm.LlmStreamResult;
import com.forgemind.core.tool.ToolExecutor;
import com.forgemind.core.tool.ToolRegistry;
import com.forgemind.core.tool.ToolResultRenderer;
import com.forgemind.model.AgentResponse;
import com.forgemind.model.AgentResult;
import com.forgemind.model.ChatMessage;
import com.forgemind.model.ContextSummary;
import com.forgemind.model.Role;
import com.forgemind.model.ToolCall;
import com.forgemind.model.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 主循环：迭代调用 LLM，执行 Tool Call，直到 LLM 返回最终答案或预算耗尽。
 *
 * <p>职责边界：本类不直接与用户交互，不感知具体 LLM / Tool 实现，也不做权限决策
 * （权限由 {@link ToolExecutor} 链路完成）。</p>
 *
 * <p>错误处理：</p>
 * <ul>
 *   <li><b>可恢复错误</b>（未知工具、参数错误、权限拒绝、工具失败/超时、路径越界等）
 *       由 ToolExecutor 转为错误 {@link ToolResult} 回灌 TOOL 消息，LLM 自我纠正，
 *       单个 Tool 失败不会终止循环；</li>
 *   <li><b>畸形响应</b>（null / 空响应 / 含空 id 或空 name 的 Tool Call）不执行任何
 *       工具，整轮判定为 invalid 并回灌提示；连续达到 {@link #INVALID_RESPONSE_THRESHOLD}
 *       次则抛 {@link InvalidToolCallException}；合法响应会重置计数；</li>
 *   <li><b>不可恢复错误</b>（迭代预算耗尽、LLM 故障、连续畸形）被捕获并封装进
 *       {@link AgentResult}，绝不向调用方裸抛，且保留部分成果与已执行工具计数。</li>
 * </ul>
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 连续畸形响应的阈值：超过则终止（架构 §5.3 错误处理矩阵）。 */
    private static final int INVALID_RESPONSE_THRESHOLD = 3;

    /** finish_reason=length 时的续写提示（内部消息，不改动用户原始任务）。 */
    private static final String CONTINUATION_PROMPT =
            "Previous response was truncated because the output limit was reached. "
                    + "Continue from where you stopped. Do not repeat completed content. "
                    + "Continue the current task.";

    /**
     * 预算耗尽前的收尾提示（内部消息）：剩余迭代 ≤5 轮时注入一次，
     * 让 LLM 停止探索性重试、收敛到最终答案，避免"清理类工具调用消耗预算
     * 导致来不及输出 final answer"而误报 failed。
     */
    private static final String BUDGET_HINT_PROMPT =
            "Iteration budget is nearly exhausted. Stop exploratory retries. "
                    + "Complete the task using the minimum necessary actions "
                    + "and provide the final answer.";

    /** 触发收尾提示的剩余迭代阈值（剩余 ≤5 轮进入收尾模式）。 */
    private static final int BUDGET_HINT_REMAINING_THRESHOLD = 5;

    private final Path workingDirectory;
    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final AgentConfig config;
    private final ProgressListener progress;

    /** 相同 shell 命令连续失败护栏（跨轮计数，命令成功即清零）。 */
    private final CommandFailureTracker failureTracker = new CommandFailureTracker();

    /** 兼容构造：使用 no-op ProgressListener。 */
    public AgentLoop(Path workingDirectory,
                     LlmClient llm,
                     ToolRegistry registry,
                     ToolExecutor executor,
                     AgentConfig config) {
        this(workingDirectory, llm, registry, executor, config, ProgressListener.NOOP);
    }

    /** M8：可注入观察层 ProgressListener（异常会被忽略，不影响核心任务）。 */
    public AgentLoop(Path workingDirectory,
                     LlmClient llm,
                     ToolRegistry registry,
                     ToolExecutor executor,
                     AgentConfig config,
                     ProgressListener progress) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.llm = Objects.requireNonNull(llm, "llm");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        this.progress = safe(Objects.requireNonNull(progress, "progress"));
    }

    public AgentResult run(String task) {
        Objects.requireNonNull(task, "task");
        AgentContext context = new AgentContext(workingDirectory, task);
        context.appendMessage(ChatMessage.system(systemPrompt()));
        context.appendMessage(ChatMessage.user(task));

        int iterations = 0;
        int toolCallCount = 0;
        String partialAnswer = null;
        int consecutiveInvalid = 0;
        int continuationCount = 0;
        boolean budgetHintInjected = false;
        try {
            while (true) {
                // M8.5：取消边界 —— 线程中断即取消（不杀运行中的 Tool，让当前
                // Tool 自然完成，循环在下一轮边界停止并返回 failed("cancelled")）。
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("agent loop cancelled: thread interrupted after {} iterations", iterations);
                    return AgentResult.failed(partialAnswer, iterations, toolCallCount, "cancelled");
                }
                if (iterations >= config.maxIterations()) {
                    throw new MaxIterationsExceededException(
                            "max iterations exceeded: " + config.maxIterations());
                }
                iterations++;

                // 预算将尽：剩余 ≤5 轮时提示 LLM 停止探索性重试、收敛收尾，
                // 避免"任务已完成但仍继续工具调用 → 预算耗尽来不及输出 final answer → failed"。
                int remaining = config.maxIterations() - iterations;
                if (!budgetHintInjected && remaining <= BUDGET_HINT_REMAINING_THRESHOLD) {
                    log.debug("iteration budget nearly exhausted (remaining={}), injecting final-answer hint",
                            remaining);
                    context.appendMessage(ChatMessage.user(BUDGET_HINT_PROMPT));
                    budgetHintInjected = true;
                }
                log.debug("loop iteration {}/{}", iterations, config.maxIterations());

                // M6/M7：进入 LLM 前压缩旧消息（token 预算优先，回退字符预算）
                ContextSummary summary = DeterministicContextSummaryExtractor.extract(context.messages());
                int removed;
                if (config.contextMaxTokens() > 0) {
                    removed = context.compactIfNeededTokens(
                            config.usableContextTokens(), TokenEstimator.DEFAULT);
                } else {
                    removed = context.compactIfNeeded(config.contextMaxChars());
                }
                if (removed > 0 && !summary.isEmpty()) {
                    context.appendMessage(ChatMessage.system(summary.render()));
                }

                // M8：LLM 响应获取（streaming 可选通道；领域决策不变）
                AgentResponse response = obtainResponse(context);
                if (response != null && response.finishReason() != null) {
                    log.debug("finish_reason={}", response.finishReason());
                }

                if (response == null) {
                    consecutiveInvalid = onInvalidResponse(
                            context, consecutiveInvalid, "null response");
                    continue;
                }
                if (!response.hasToolCalls()) {
                    if (response.content() == null || response.content().isBlank()) {
                        consecutiveInvalid = onInvalidResponse(
                                context, consecutiveInvalid, "empty response");
                        continue;
                    }
                    if ("length".equals(response.finishReason())) {
                        // M7：截断续写；超过 maxContinuationAttempts 或禁用时结束
                        partialAnswer = response.content();
                        if (continuationCount < config.maxContinuationAttempts()) {
                            continuationCount++;
                            context.appendMessage(ChatMessage.user(CONTINUATION_PROMPT));
                            continue;
                        }
                        return AgentResult.completed(response.content(), iterations, toolCallCount);
                    }
                    return AgentResult.completed(response.content(), iterations, toolCallCount);
                }
                if (containsInvalidToolCall(response.toolCalls())) {
                    // 任一 Tool Call 非法（空 id / 空 name）→ 整轮 invalid，任何工具都不执行
                    consecutiveInvalid = onInvalidResponse(
                            context, consecutiveInvalid, "invalid tool call (empty id or name)");
                    continue;
                }

                consecutiveInvalid = 0;
                continuationCount = 0;
                if (response.content() != null && !response.content().isBlank()) {
                    partialAnswer = response.content();
                }
                toolCallCount += response.toolCalls().size();
                appendAssistantToolCallMessage(context, response);
                for (ToolCall call : response.toolCalls()) {
                    progress.onToolCallStarted(call.name());
                    ToolResult result = executor.execute(call.name(), call.arguments());
                    progress.onToolResult(call.name(), result.success());
                    log.debug("tool '{}' -> success={} truncated={}",
                            call.name(), result.success(), result.truncated());
                    context.appendMessage(ChatMessage.tool(call.id(),
                            ToolResultRenderer.render(result, call.name(), config.toolOutputLimit())));
                    // 重复失败护栏：仅 shell tool 计数；成功清零，失败按连续次数注入提示（只提示不阻断）
                    if ("shell".equals(call.name())) {
                        String command = commandOf(call);
                        if (command != null) {
                            if (result.success()) {
                                failureTracker.recordSuccess(command);
                            } else {
                                int consecutive = failureTracker.recordFailure(command);
                                String hint = CommandFailureTracker.hintFor(consecutive);
                                if (hint != null) {
                                    context.appendMessage(ChatMessage.user(hint));
                                }
                            }
                        }
                    }
                }
            }
        } catch (AgentException e) {
            log.warn("agent loop terminated with error: {}", e.getMessage());
            return AgentResult.failed(partialAnswer, iterations, toolCallCount, e.getMessage());
        }
    }

    /**
     * 处理一次畸形响应：计数 + 回灌提示（阈值内），达到阈值抛
     * {@link InvalidToolCallException}（由 run 的 catch 封装为 AgentResult.failed）。
     * 普通 Tool 执行失败不计入本计数。
     */
    private static int onInvalidResponse(AgentContext context, int consecutiveInvalid, String reason) {
        int count = consecutiveInvalid + 1;
        log.warn("invalid LLM response ({}/{}): {}", count, INVALID_RESPONSE_THRESHOLD, reason);
        if (count >= INVALID_RESPONSE_THRESHOLD) {
            throw new InvalidToolCallException(
                    "LLM returned invalid responses " + count + " consecutive times: " + reason);
        }
        context.appendMessage(ChatMessage.user(
                "(invalid response from you: " + reason + ") Please reply with a valid response."));
        return count;
    }

    private static boolean containsInvalidToolCall(List<ToolCall> toolCalls) {
        for (ToolCall call : toolCalls) {
            if (call == null) {
                return true;
            }
            if (call.name() == null || call.name().isBlank()) {
                return true;
            }
            if (call.id() == null || call.id().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取一轮 LLM 响应：LlmClient 支持流式（LlmStreamClient）时走 streaming 通道
     * （阻塞收集完整 AgentResponse，delta 仅经 ProgressListener 展示），否则回退 chat()。
     */
    private AgentResponse obtainResponse(AgentContext context) {
        if (llm instanceof LlmStreamClient streamClient) {
            CollectingListener listener = new CollectingListener();
            streamClient.stream(context.messages(), listener);
            if (listener.error != null) {
                throw listener.error;
            }
            if (listener.result == null) {
                throw new LlmException("stream ended without a result");
            }
            return listener.result.response();
        }
        return llm.chat(context.messages());
    }

    /** 阻塞收集流式结果；delta 转发给 ProgressListener（不写 Context）。 */
    private final class CollectingListener implements LlmStreamListener {
        LlmStreamResult result;
        LlmException error;

        @Override
        public void onTextDelta(String delta) {
            progress.onTextDelta(delta);
        }

        @Override
        public void onToolCallDelta(String idDelta, String nameDelta, String argumentsDelta) {
            // 分片不展示、不执行；完整 ToolCall 由 Accumulator 组装进最终响应
        }

        @Override
        public void onComplete(LlmStreamResult result) {
            this.result = result;
        }

        @Override
        public void onError(LlmException error) {
            this.error = error;
        }
    }

    /** 观察层异常不得影响核心任务：包装为忽略（仅记录日志）。 */
    private static ProgressListener safe(ProgressListener delegate) {
        return new ProgressListener() {
            @Override
            public void onTextDelta(String delta) {
                try {
                    delegate.onTextDelta(delta);
                } catch (RuntimeException e) {
                    log.warn("progress listener onTextDelta ignored: {}", e.getMessage());
                }
            }

            @Override
            public void onToolCallStarted(String toolName) {
                try {
                    delegate.onToolCallStarted(toolName);
                } catch (RuntimeException e) {
                    log.warn("progress listener onToolCallStarted ignored: {}", e.getMessage());
                }
            }

            @Override
            public void onToolResult(String toolName, boolean success) {
                try {
                    delegate.onToolResult(toolName, success);
                } catch (RuntimeException e) {
                    log.warn("progress listener onToolResult ignored: {}", e.getMessage());
                }
            }
        };
    }

    private static void appendAssistantToolCallMessage(AgentContext context, AgentResponse response) {
        if (response.content() != null && !response.content().isBlank()) {
            context.appendMessage(new ChatMessage(Role.ASSISTANT,
                    response.content(), null, response.toolCalls()));
        } else {
            context.appendMessage(ChatMessage.assistantToolCalls(response.toolCalls()));
        }
    }

    /** 从 Tool Call 参数中提取 shell 命令；缺失或非 String 返回 null（护栏跳过）。 */
    private static String commandOf(ToolCall call) {
        if (call.arguments() == null) {
            return null;
        }
        Object raw = call.arguments().get("command");
        return raw instanceof String s ? s : null;
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        // 环境感知：OS / Shell / 工作目录 / 命令执行规则（避免模型按错误平台的命令习惯盲试）
        sb.append(EnvironmentInfo.describe(
                System.getProperty("os.name", ""), config.toolLimits().shellType(), workingDirectory));
        sb.append('\n');
        sb.append("You are a coding agent working in the directory: ")
                .append(workingDirectory).append('\n');
        sb.append("You can use the following tools:\n");
        registry.all().forEach((name, tool) ->
                sb.append("- ").append(name).append(": ").append(tool.description()).append('\n'));
        sb.append("When you need to inspect or modify the codebase, call the appropriate tool. ")
                .append("When the task is complete, reply with the final answer without tool calls.");
        return sb.toString();
    }
}
