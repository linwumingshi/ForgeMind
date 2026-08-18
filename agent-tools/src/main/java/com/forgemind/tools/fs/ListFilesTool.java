package com.forgemind.tools.fs;

import com.forgemind.core.context.ToolContext;
import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.tool.AgentTool;
import com.forgemind.model.ToolParameter;
import com.forgemind.model.ToolResult;
import com.forgemind.model.ToolSchema;
import com.forgemind.tools.ToolSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * list_files：列出工作区内指定目录下的文件与目录。
 *
 * <p>单层列出（默认）；{@code recursive=true} 时深度优先遍历，深度受
 * {@code maxDepth}（1..3，默认 3）限制，不跟随符号链接。结果条数超过
 * 限额时截断并置 {@code truncated} 标记。</p>
 */
public final class ListFilesTool implements AgentTool {

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List files and directories under a path inside the workspace. "
                + "Optional recursive listing with a depth limit.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "path", new ToolParameter("string",
                        "directory to list (relative or absolute, default: workspace root)"),
                "recursive", new ToolParameter("boolean",
                        "whether to list recursively (default false)"),
                "maxDepth", new ToolParameter("integer",
                        "max recursion depth when recursive, between 1 and 3 (default 3)")),
                List.of());
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Path start = ToolSupport.resolvePath(context, arguments.get("path"));
        if (start == null) {
            return ToolResult.failure("path rejected: " + arguments.get("path"));
        }
        boolean recursive = ToolSupport.boolArg(arguments, "recursive", false);
        int maxDepth = context.limits().listFilesMaxDepth();
        if (arguments.get("maxDepth") != null) {
            maxDepth = ToolSupport.intArg(arguments, "maxDepth", maxDepth);
            if (maxDepth < 1 || maxDepth > context.limits().listFilesMaxDepth()) {
                return ToolResult.failure("maxDepth must be between 1 and "
                        + context.limits().listFilesMaxDepth() + ", got " + maxDepth);
            }
        }
        if (!Files.exists(start)) {
            return ToolResult.failure("path not found: " + start);
        }
        if (!Files.isDirectory(start)) {
            return ToolResult.failure("not a directory: " + start);
        }

        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        int maxEntries = context.limits().listFilesMaxEntries();
        try {
            if (recursive) {
                walk(start, start, 1, maxDepth, sb, count, maxEntries);
            } else {
                listLevel(start, start, sb, count, maxEntries);
            }
        } catch (IOException e) {
            return ToolResult.failure("failed to list '" + start + "': " + e.getMessage());
        }
        boolean truncated = count[0] >= maxEntries;
        return new ToolResult(null, true, sb.toString(), null, null, truncated);
    }

    private static void listLevel(Path dir, Path base, StringBuilder sb, int[] count, int maxEntries)
            throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (count[0] >= maxEntries) {
                    break;
                }
                appendEntry(sb, entry, base.relativize(entry));
                count[0]++;
            }
        }
    }

    private static void walk(Path dir, Path base, int depth, int maxDepth,
                             StringBuilder sb, int[] count, int maxEntries) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (count[0] >= maxEntries) {
                    return;
                }
                appendEntry(sb, entry, base.relativize(entry));
                count[0]++;
                if (depth < maxDepth && isRealDirectory(entry)) {
                    walk(entry, base, depth + 1, maxDepth, sb, count, maxEntries);
                }
            }
        }
    }

    private static void appendEntry(StringBuilder sb, Path entry, Path displayName) throws IOException {
        String type = entryType(entry);
        String size = switch (type) {
            case "DIR", "LINK" -> "-";
            default -> String.valueOf(Files.size(entry));
        };
        String modified = Files.getLastModifiedTime(entry).toInstant().toString();
        sb.append(type).append('\t').append(displayName).append('\t')
                .append(size).append('\t').append(modified).append('\n');
    }

    /** 类型判定：符号链接标 LINK；目录（不跟随链接）标 DIR；其余 FILE。 */
    private static String entryType(Path entry) {
        if (Files.isSymbolicLink(entry)) {
            return "LINK";
        }
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            return "DIR";
        }
        return "FILE";
    }

    private static boolean isRealDirectory(Path entry) {
        return Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    }
}
