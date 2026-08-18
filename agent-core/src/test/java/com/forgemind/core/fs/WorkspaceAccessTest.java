package com.forgemind.core.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.exception.PathEscapeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceAccess 安全边界测试：正常路径放行，逃逸路径一律拒绝。
 */
class WorkspaceAccessTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private WorkspaceAccess access;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("ws"));
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}");
        access = new WorkspaceAccess(workspace);
    }

    // ---------- 正常情况 ----------

    @Test
    void resolvesRelativePath() {
        assertEquals(workspace.resolve("pom.xml"), access.resolve("pom.xml"));
        assertEquals(workspace.resolve("src/main/java/App.java"), access.resolve("src/main/java/App.java"));
    }

    @Test
    void resolvesCurrentDirectoryToRoot() {
        assertEquals(workspace, access.resolve("."));
        assertEquals(workspace, access.resolve("./"));
    }

    @Test
    void resolvesAbsolutePathInside() {
        assertEquals(workspace.resolve("pom.xml"),
                access.resolve(workspace.resolve("pom.xml").toString()));
    }

    @Test
    void resolvesExistingFileWithRealPathCheck() {
        // 目标存在时内部会做 toRealPath 二次校验，正常文件应放行
        assertEquals(workspace.resolve("pom.xml"), access.resolve("pom.xml"));
        assertTrue(access.isInside(workspace.resolve("pom.xml")));
    }

    // ---------- 必须拒绝 ----------

    @Test
    void rejectsParentTraversal() {
        assertThrows(PathEscapeException.class, () -> access.resolve("../secret.txt"));
    }

    @Test
    void rejectsDoubleParentTraversal() {
        assertThrows(PathEscapeException.class, () -> access.resolve("../../secret.txt"));
    }

    @Test
    void rejectsTraversalThroughSubdirectory() {
        assertThrows(PathEscapeException.class, () -> access.resolve("src/../../secret.txt"));
    }

    @Test
    void rejectsAbsolutePathOutside() {
        Path outside = tempDir.resolve("outside.txt");
        assertThrows(PathEscapeException.class, () -> access.resolve(outside.toString()));
    }

    @Test
    void rejectsEmptyAndBlankPaths() {
        assertThrows(PathEscapeException.class, () -> access.resolve(""));
        assertThrows(PathEscapeException.class, () -> access.resolve("   "));
    }

    @Test
    void rejectsInvalidPath() {
        assertThrows(PathEscapeException.class, () -> access.resolve("bad\u0000path"));
    }

    @Test
    void isInsideDistinguishesInAndOut() {
        assertTrue(access.isInside(workspace.resolve("pom.xml")));
        assertFalse(access.isInside(tempDir.resolve("ws2")));
        assertFalse(access.isInside(tempDir));
    }

    // ---------- Symbolic Link（平台支持时） ----------

    @Test
    void rejectsSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        assertThrows(PathEscapeException.class, () -> access.resolve("link/secret.txt"));
        assertFalse(access.isInside(link));
    }

    @Test
    void allowsSymlinkPointingInsideWorkspace() throws IOException {
        Path target = Files.createDirectories(workspace.resolve("real"));
        Files.writeString(target.resolve("f.txt"), "x");
        Path link = workspace.resolve("alias");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        assertEquals(workspace.resolve("alias/f.txt"), access.resolve("alias/f.txt"));
        assertTrue(access.isInside(link));
    }

    // ---------- Windows 特殊情况 ----------

    @Test
    void rejectsWriteThroughSymlinkPointingOutsideForNonExistentTarget() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        // 目标文件不存在：经符号链接父目录写入必须被拒绝（"最近存在祖先"检查）
        assertThrows(PathEscapeException.class, () -> access.resolve("link/newfile.txt"));
        assertFalse(access.isInside(workspace.resolve("link/newfile.txt")));
    }

    @Test
    void rejectsMultiLevelDirCreationThroughSymlink() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        // 经逃逸链接继续创建多级目录：candidate 与其直接祖先都不存在，
        // 必须通过"最近存在祖先"(link→outside) 检查拒绝
        assertThrows(PathEscapeException.class, () -> access.resolve("link/a/b/c.txt"));
        assertFalse(access.isInside(workspace.resolve("link/a/b/c.txt")));
    }

    @Test
    void allowsWriteThroughSymlinkPointingInsideWorkspace() throws IOException {
        Path realDir = Files.createDirectories(workspace.resolve("real"));
        Path link = workspace.resolve("alias");
        try {
            Files.createSymbolicLink(link, realDir);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e);
        }
        // 站内符号链接父目录 + 不存在的目标文件：应放行
        assertEquals(workspace.resolve("alias/new.txt"), access.resolve("alias/new.txt"));
        assertTrue(access.isInside(workspace.resolve("alias/new.txt")));
    }

    @Test
    void windowsPathComparisonIsCaseInsensitive() {
        Assumptions.assumeTrue(isWindows());
        Path resolved = access.resolve("POM.XML");
        assertTrue(resolved.startsWith(workspace));
        assertTrue(access.isInside(workspace.resolve("POM.XML")));
    }

    @Test
    void windowsPathOnOtherDriveIsOutside() {
        Assumptions.assumeTrue(isWindows());
        char drive = workspace.getRoot().toString().charAt(0);
        char other = drive == 'C' ? 'D' : 'C';
        Path otherDrive = Path.of(other + ":\\some\\path");
        assertFalse(access.isInside(otherDrive));
        assertThrows(PathEscapeException.class, () -> access.resolve(otherDrive.toString()));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
