package com.forgemind.tools.fs;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.TextFiles;
import com.forgemind.tools.ToolSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * read_file：读取工作区内的文本文件（UTF-8）。
 *
 * <p>约束：文件不存在/是目录 → 明确错误；超过大小上限（默认 1MB）→ 报错不截断
 * （架构 §6.3）；二进制文件（扩展名或 NUL 探测）→ 拒绝读取。</p>
 */
public final class ReadFileTool implements AgentTool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a text file inside the workspace (UTF-8). "
                + "Fails for binary files and files exceeding the size limit.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "path", new ToolParameter("string", "file path (relative or absolute)")),
                List.of("path"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Path path = ToolSupport.resolvePath(context, arguments.get("path"));
        if (path == null) {
            return ToolResult.failure("path rejected: " + arguments.get("path"));
        }
        if (!Files.exists(path)) {
            return ToolResult.failure("file not found: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("is a directory: " + path);
        }
        long maxBytes = context.limits().readFileMaxBytes();
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                return ToolResult.failure("file too large: " + size + " bytes (limit " + maxBytes + ")");
            }
            if (TextFiles.isBinary(path)) {
                return ToolResult.failure("binary file, cannot read as text: " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            return ToolResult.success(content);
        } catch (IOException e) {
            return ToolResult.failure("failed to read '" + path + "': " + e.getMessage());
        }
    }
}
