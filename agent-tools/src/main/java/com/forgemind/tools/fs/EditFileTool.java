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
                // P2.3：失败诊断增强（不改匹配语义）——提示常见原因，引导先 read_file 再看
                return ToolResult.failure("oldText not found in " + path
                        + ". Possible causes: file content has changed since it was read, "
                        + "case mismatch, whitespace/indentation mismatch, or line-ending mismatch "
                        + "(CRLF vs LF). Re-read the file with read_file first, then retry edit_file "
                        + "with the exact text.");
            }
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                // P2.3：明确匹配次数，要求更精确的 oldText（不自动选择其中一个）
                int matches = countMatches(content, oldText);
                return ToolResult.failure("oldText matched " + matches
                        + " times in " + path + "; provide a more precise oldText "
                        + "that matches exactly once.");
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

    /** 统计 oldText 在 content 中出现的次数（仅用于失败诊断，不改匹配语义）。 */
    private static int countMatches(String content, String oldText) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = content.indexOf(oldText, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + oldText.length();
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
