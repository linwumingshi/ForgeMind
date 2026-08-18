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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * edit_file：局部替换文件内容。
 *
 * <p>要求 oldText 必须存在且唯一匹配；0 次或 ≥2 次匹配都失败且不修改原文件。
 * 写入采用"同目录临时文件 + 原子移动"，保证任何失败都不破坏原文件。</p>
 */
public final class EditFileTool implements AgentTool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace a unique occurrence of oldText with newText in a file inside the workspace. "
                + "Fails if oldText is missing or matches multiple times.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "path", new ToolParameter("string", "file path (relative or absolute)"),
                "oldText", new ToolParameter("string", "existing text to replace (must be unique)"),
                "newText", new ToolParameter("string", "replacement text")),
                List.of("path", "oldText", "newText"));
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
        Object rawOld = arguments.get("oldText");
        Object rawNew = arguments.get("newText");
        String oldText = rawOld instanceof String s ? s : "";
        String newText = rawNew instanceof String s ? s : "";
        if (oldText.isEmpty()) {
            return ToolResult.failure("oldText must not be empty");
        }
        long oldTextMax = context.limits().editFileOldTextMaxBytes();
        if (oldText.length() > oldTextMax) {
            return ToolResult.failure("oldText too large: " + oldText.length()
                    + " chars (limit " + oldTextMax + ")");
        }
        if (!Files.exists(path)) {
            return ToolResult.failure("file not found: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("is a directory: " + path);
        }
        try {
            long maxBytes = context.limits().readFileMaxBytes();
            if (Files.size(path) > maxBytes) {
                return ToolResult.failure("file too large: " + Files.size(path)
                        + " bytes (limit " + maxBytes + ")");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) {
                return ToolResult.failure("oldText not found in " + path);
            }
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                return ToolResult.failure("oldText matched multiple times in " + path);
            }
            String updated = content.substring(0, first) + newText
                    + content.substring(first + oldText.length());

            Path tmp = path.resolveSibling("." + path.getFileName() + ".forgemind.tmp");
            try {
                Files.writeString(tmp, updated, StandardCharsets.UTF_8);
                atomicMove(tmp, path);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                return ToolResult.failure("edit failed: " + e.getMessage());
            }
            return ToolResult.success("replaced 1 occurrence in " + path);
        } catch (IOException e) {
            return ToolResult.failure("failed to read '" + path + "': " + e.getMessage());
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
