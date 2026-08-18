package com.forgemind.core.context;

import com.forgemind.model.ChatMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Agent 运行的上下文。
 *
 * <p>{@code workingDirectory} 创建后不可变，是路径围栏（WorkspaceAccess）的锚点；
 * {@code conversation} 只能通过 {@link #appendMessage} 追加，外部只能读取不可修改的视图。</p>
 */
public final class AgentContext {

    private final Path workingDirectory;
    private final String currentTask;
    private final List<ChatMessage> conversation = new ArrayList<>();

    public AgentContext(Path workingDirectory, String currentTask) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.currentTask = Objects.requireNonNull(currentTask, "currentTask");
    }

    /** 工作目录（绝对、normalize 后，不可变）。 */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /** 当前任务描述。 */
    public String currentTask() {
        return currentTask;
    }

    /** 追加一条消息到对话历史。 */
    public void appendMessage(ChatMessage message) {
        conversation.add(Objects.requireNonNull(message, "message"));
    }

    /** 对话历史的不可修改视图。 */
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(conversation);
    }

    public int messageCount() {
        return conversation.size();
    }
}
