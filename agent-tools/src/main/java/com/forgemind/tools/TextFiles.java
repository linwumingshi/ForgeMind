package com.forgemind.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文本/二进制文件探测（MVP 启发式）：
 * <ol>
 *   <li>已知二进制扩展名（.class/.jar/.png/...）→ 二进制；</li>
 *   <li>前 8KB 内存在 NUL 字节 → 二进制；</li>
 *   <li>无法读取时按二进制处理（避免误读）。</li>
 * </ol>
 */
public final class TextFiles {

    private static final int HEAD_BYTES = 8192;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "class", "jar", "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp",
            "pdf", "zip", "gz", "tar", "7z", "rar", "exe", "dll", "so", "dylib",
            "bin", "dat", "obj", "o", "a", "lib", "woff", "woff2", "ttf", "eot",
            "mp3", "mp4", "avi", "mkv", "mov", "wav", "flac", "iso", "dmg", "psd",
            "pyc", "pyd", "rlib", "sqlite", "db", "wasm");

    private TextFiles() {
    }

    /** 判断文件是否为二进制。 */
    public static boolean isBinary(Path path) {
        if (hasBinaryExtension(path)) {
            return true;
        }
        try (InputStream in = Files.newInputStream(path)) {
            return containsNullByte(in.readNBytes(HEAD_BYTES));
        } catch (IOException e) {
            return true;
        }
    }

    /** 字节头是否包含 NUL 字节。 */
    public static boolean containsNullByte(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** 按扩展名判断是否为二进制（无扩展名返回 false）。 */
    public static boolean hasBinaryExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
