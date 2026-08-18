package com.forgemind.tools.fs;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.ToolSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * write_file：创建或覆盖工作区内的文件（UTF-8）。
 *
 * <p>自动创建父目录；拒绝写入工作区根目录本身；越界/符号链接逃逸路径由
 * WorkspaceAccess 围栏拦截。权限由 ToolExecutor 按 WRITE 范围决策。</p>
 */
public final class WriteFileTool implements AgentTool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create or overwrite a file inside the workspace (UTF-8). "
                + "Parent directories are created automatically.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "path", new ToolParameter("string", "file path (relative or absolute)"),
                "content", new ToolParameter("string", "full file content to write")),
                List.of("path", "content"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.WRITE;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Path path = ToolSupport.resolvePath(context, arguments.get("path"));
        if (path == null) {
            return ToolResult.failure("path rejected: " + arguments.get("path"));
        }
        if (path.equals(context.workspace().workspaceRoot())) {
            return ToolResult.failure("cannot write to workspace root: " + path);
        }
        Object rawContent = arguments.get("content");
        String content = rawContent instanceof String s ? s : "";
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return ToolResult.success("wrote " + bytes.length + " bytes to " + path);
        } catch (IOException e) {
            return ToolResult.failure("write failed: " + e.getMessage());
        }
    }
}
