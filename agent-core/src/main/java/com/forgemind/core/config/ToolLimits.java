package com.forgemind.core.config;

import com.forgemind.core.exception.ConfigException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Tool 运行限额配置（对齐架构 §8.2 的 ToolLimits）。
 *
 * <p>M2 阶段独立注入 {@code ToolContext}/{@code DefaultToolExecutor}，
 * 暂不并入 {@link AgentConfig}；M4 统一配置体系时再合并。</p>
 *
 * @param listFilesMaxEntries      list_files 单次结果上限（默认 500）
 * @param listFilesMaxDepth        list_files 递归最大深度（默认 3）
 * @param readFileMaxBytes         read_file/edit_file 读取文件大小上限（默认 1MB）
 * @param editFileOldTextMaxBytes  edit_file 的 oldText 长度上限（默认 64KB）
 * @param searchMaxResults         search 结果条数上限（默认 200）
 * @param searchMaxFileBytes       search 跳过超过该大小的文件（默认 1MB）
 * @param searchIgnoreDirs         search 跳过的目录名（默认 .git/node_modules/target/build/.idea）
 * @param outputLimit              shell 单流（stdout/stderr）输出上限（默认 64KB）
 * @param shellTimeout             shell 命令超时（默认 60s）
 * @param shellType                shell 类型（默认 CMD）
 */
public record ToolLimits(
        int listFilesMaxEntries,
        int listFilesMaxDepth,
        long readFileMaxBytes,
        long editFileOldTextMaxBytes,
        int searchMaxResults,
        long searchMaxFileBytes,
        List<String> searchIgnoreDirs,
        long outputLimit,
        Duration shellTimeout,
        ShellType shellType) {

    public static final int DEFAULT_LIST_FILES_MAX_ENTRIES = 500;
    public static final int DEFAULT_LIST_FILES_MAX_DEPTH = 3;
    public static final long DEFAULT_READ_FILE_MAX_BYTES = 1024L * 1024;
    public static final long DEFAULT_EDIT_FILE_OLD_TEXT_MAX_BYTES = 64L * 1024;
    public static final int DEFAULT_SEARCH_MAX_RESULTS = 200;
    public static final long DEFAULT_SEARCH_MAX_FILE_BYTES = 1024L * 1024;
    public static final List<String> DEFAULT_SEARCH_IGNORE_DIRS =
            List.of(".git", "node_modules", "target", "build", ".idea");
    public static final long DEFAULT_OUTPUT_LIMIT = 64L * 1024;
    public static final Duration DEFAULT_SHELL_TIMEOUT = Duration.ofSeconds(60);

    public ToolLimits {
        requirePositive(listFilesMaxEntries, "listFilesMaxEntries");
        requirePositive(listFilesMaxDepth, "listFilesMaxDepth");
        requirePositive(readFileMaxBytes, "readFileMaxBytes");
        requirePositive(editFileOldTextMaxBytes, "editFileOldTextMaxBytes");
        requirePositive(searchMaxResults, "searchMaxResults");
        requirePositive(searchMaxFileBytes, "searchMaxFileBytes");
        requirePositive(outputLimit, "outputLimit");
        Objects.requireNonNull(searchIgnoreDirs, "searchIgnoreDirs");
        Objects.requireNonNull(shellTimeout, "shellTimeout");
        if (shellTimeout.isZero() || shellTimeout.isNegative()) {
            throw new ConfigException("shellTimeout must be positive: " + shellTimeout);
        }
        Objects.requireNonNull(shellType, "shellType");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new ConfigException(name + " must be positive: " + value);
        }
    }

    public static ToolLimits defaults() {
        return new ToolLimits(
                DEFAULT_LIST_FILES_MAX_ENTRIES,
                DEFAULT_LIST_FILES_MAX_DEPTH,
                DEFAULT_READ_FILE_MAX_BYTES,
                DEFAULT_EDIT_FILE_OLD_TEXT_MAX_BYTES,
                DEFAULT_SEARCH_MAX_RESULTS,
                DEFAULT_SEARCH_MAX_FILE_BYTES,
                DEFAULT_SEARCH_IGNORE_DIRS,
                DEFAULT_OUTPUT_LIMIT,
                DEFAULT_SHELL_TIMEOUT,
                ShellType.CMD);
    }

    public ToolLimits withListFilesMaxEntries(int value) {
        return new ToolLimits(value, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withListFilesMaxDepth(int value) {
        return new ToolLimits(listFilesMaxEntries, value, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withReadFileMaxBytes(long value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, value, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withEditFileOldTextMaxBytes(long value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, value,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withSearchMaxResults(int value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                value, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withSearchMaxFileBytes(long value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, value, searchIgnoreDirs, outputLimit, shellTimeout, shellType);
    }

    public ToolLimits withOutputLimit(long value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, value, shellTimeout, shellType);
    }

    public ToolLimits withShellTimeout(Duration value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, value, shellType);
    }

    public ToolLimits withShellType(ShellType value) {
        return new ToolLimits(listFilesMaxEntries, listFilesMaxDepth, readFileMaxBytes, editFileOldTextMaxBytes,
                searchMaxResults, searchMaxFileBytes, searchIgnoreDirs, outputLimit, shellTimeout, value);
    }
}
