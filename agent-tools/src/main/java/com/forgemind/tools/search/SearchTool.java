package com.forgemind.tools.search;

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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * search：递归搜索工作区内文本文件，大小写不敏感子串匹配。
 *
 * <p>输出匹配行 + 行号 + ±2 行上下文（架构 §6.3）。跳过 ignore 目录
 * （.git/node_modules/target/build/.idea）、二进制文件、超过大小上限的文件；
 * 不跟随符号链接（防逃逸与循环）。结果条数超过限额时截断并置 truncated 标记。</p>
 */
public final class SearchTool implements AgentTool {

    private static final int MAX_LINE_LENGTH = 200;

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search text files inside the workspace for a case-insensitive substring. "
                + "Returns matching lines with line numbers and context.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(Map.of(
                "query", new ToolParameter("string", "text to search for"),
                "path", new ToolParameter("string",
                        "directory to search (relative or absolute, default: workspace root)")),
                List.of("query"));
    }

    @Override
    public PermissionScope permissionScope() {
        return PermissionScope.READ;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        Object rawQuery = arguments.get("query");
        String query = rawQuery instanceof String s ? s : "";
        if (query.isEmpty()) {
            return ToolResult.failure("query must not be empty");
        }
        Path start = ToolSupport.resolvePath(context, arguments.get("path"));
        if (start == null) {
            return ToolResult.failure("path rejected: " + arguments.get("path"));
        }
        if (!Files.exists(start)) {
            return ToolResult.failure("path not found: " + start);
        }
        if (!Files.isDirectory(start)) {
            return ToolResult.failure("not a directory: " + start);
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        int maxResults = context.limits().searchMaxResults();
        searchDir(context, start, start, lowerQuery, sb, count, maxResults);
        boolean truncated = count[0] >= maxResults;
        return new ToolResult(null, true, sb.toString(), null, null, truncated);
    }

    private static void searchDir(ToolContext context, Path dir, Path base, String lowerQuery,
                                  StringBuilder sb, int[] count, int maxResults) {
        if (count[0] >= maxResults) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (count[0] >= maxResults) {
                    return;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (!context.limits().searchIgnoreDirs().contains(entry.getFileName().toString())) {
                        searchDir(context, entry, base, lowerQuery, sb, count, maxResults);
                    }
                } else if (!Files.isSymbolicLink(entry)) {
                    searchFile(context, entry, base, lowerQuery, sb, count, maxResults);
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时跳过（不中断整体搜索）
        }
    }

    private static void searchFile(ToolContext context, Path file, Path base, String lowerQuery,
                                   StringBuilder sb, int[] count, int maxResults) {
        if (count[0] >= maxResults) {
            return;
        }
        try {
            if (Files.size(file) > context.limits().searchMaxFileBytes()) {
                return;
            }
            if (TextFiles.isBinary(file)) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (count[0] >= maxResults) {
                    return;
                }
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    appendMatch(sb, base.relativize(file).toString(), i, lines);
                    count[0]++;
                }
            }
        } catch (IOException ignored) {
            // 文件不可读时跳过
        }
    }

    private static void appendMatch(StringBuilder sb, String relPath, int lineIndex, List<String> lines) {
        sb.append("--- ").append(relPath).append(':').append(lineIndex + 1).append(" ---\n");
        int from = Math.max(0, lineIndex - 2);
        int to = Math.min(lines.size() - 1, lineIndex + 2);
        for (int i = from; i <= to; i++) {
            sb.append(i == lineIndex ? '>' : ' ')
                    .append(String.format(Locale.ROOT, "%5d | %s%n", i + 1, truncate(lines.get(i))));
        }
    }

    private static String truncate(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + "...";
    }
}
