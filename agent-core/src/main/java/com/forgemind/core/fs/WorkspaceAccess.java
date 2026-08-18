package com.forgemind.core.fs;

import com.forgemind.core.exception.PathEscapeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 工作区路径围栏：所有文件类 Tool 必须经它解析路径，保证解析结果位于工作目录
 * 之内，防止 ".." 逃逸、绝对路径越界与符号链接逃逸。
 *
 * <p>校验分三层（见 {@link #isSafe(Path)}）：</p>
 * <ul>
 *   <li>词法检查：normalize 后必须 startsWith 工作目录（Windows 下 Path 比较大小写不敏感）；</li>
 *   <li>真实路径检查：目标已存在时用 {@code toRealPath()} 解析符号链接，再校验一次；</li>
 *   <li>目标不存在时检查"最近存在的祖先"的真实路径（防止经符号链接父目录
 *       写入不存在的文件而逃逸）。</li>
 * </ul>
 *
 * <p>已知限制：若工作目录本身不存在，无法做真实路径校验（此时仅词法校验）；
 * Windows 上创建符号链接需要开发者模式/管理员权限，相关行为见测试与测试报告。</p>
 */
public final class WorkspaceAccess {

    private final Path workspaceRoot;   // 词法锚点（绝对 + normalize）
    private final Path realRoot;        // 真实锚点（root 存在时 toRealPath，否则 null）

    public WorkspaceAccess(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.realRoot = realPathIfExists(this.workspaceRoot);
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 解析路径（相对路径以工作目录为根；绝对路径必须位于工作目录内）。
     * 解析结果必须通过词法检查与真实路径检查（防符号链接逃逸，含"符号链接
     * 父目录 + 不存在的目标文件"的写入场景）。
     *
     * @throws PathEscapeException 路径越界或非法
     */
    public Path resolve(String path) {
        Objects.requireNonNull(path, "path");
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            throw new PathEscapeException("empty path");
        }
        Path candidate;
        try {
            Path raw = Path.of(trimmed);
            candidate = raw.isAbsolute() ? raw.normalize() : workspaceRoot.resolve(raw).normalize();
        } catch (RuntimeException e) { // InvalidPathException 等
            throw new PathEscapeException("invalid path: " + path, e);
        }
        if (!isSafe(candidate)) {
            throw new PathEscapeException(
                    "path escapes workspace root '" + workspaceRoot + "': " + path);
        }
        return candidate;
    }

    /** 判断路径是否位于工作区之内（词法检查 + 真实路径检查）。 */
    public boolean isInside(Path path) {
        Objects.requireNonNull(path, "path");
        return isSafe(path.toAbsolutePath().normalize());
    }

    /**
     * 安全判定：
     * <ol>
     *   <li>词法检查：normalize 后必须 startsWith 工作目录（Windows 下 Path 比较大小写不敏感）；</li>
     *   <li>目标已存在：{@code toRealPath()} 解析符号链接后必须在真实根内；</li>
     *   <li>目标不存在：检查"最近存在的祖先"的真实路径（防止经符号链接父目录
     *       写入不存在的文件而逃逸）。</li>
     * </ol>
     */
    private boolean isSafe(Path candidate) {
        if (!isLexicallyInside(candidate)) {
            return false;
        }
        Path real = realPathIfExists(candidate);
        if (real != null) {
            return isRealInside(real);
        }
        Path ancestor = candidate.getParent();
        while (ancestor != null) {
            Path realAncestor = realPathIfExists(ancestor);
            if (realAncestor != null) {
                return isRealInside(realAncestor);
            }
            ancestor = ancestor.getParent();
        }
        return true; // 根都不存在时无法解析符号链接，词法检查已通过
    }

    private boolean isLexicallyInside(Path path) {
        return path.startsWith(workspaceRoot);
    }

    private boolean isRealInside(Path realPath) {
        return realRoot == null || realPath.startsWith(realRoot);
    }

    private static Path realPathIfExists(Path path) {
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to resolve real path of " + path, e);
        }
    }
}
