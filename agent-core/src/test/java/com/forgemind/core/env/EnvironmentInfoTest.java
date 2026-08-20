package com.forgemind.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.config.ShellType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnvironmentInfoTest {

    private static final Path WORK_DIR = Path.of("C:/workspace/project");

    @Test
    void windowsWithCmdShell() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: Windows"));
        assertTrue(text.contains("- Shell: cmd.exe"));
        assertTrue(text.contains("- Working directory: C:\\workspace\\project"));
    }

    @Test
    void windowsWithPowerShell() {
        String text = EnvironmentInfo.describe("Windows 10", ShellType.POWERSHELL, WORK_DIR);
        assertTrue(text.contains("- Shell: powershell.exe"));
    }

    @Test
    void linuxUsesSh() {
        String text = EnvironmentInfo.describe("Linux", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: Linux"));
        assertTrue(text.contains("- Shell: sh"));
    }

    @Test
    void macOSUsesSh() {
        String text = EnvironmentInfo.describe("Mac OS X", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("- OS: macOS"));
        assertTrue(text.contains("- Shell: sh"));
    }

    @Test
    void unknownOsKeepsOriginalLabel() {
        assertEquals("FreeBSD", EnvironmentInfo.osLabel("FreeBSD"));
    }

    @Test
    void nullOsNameFallsBackToUnknown() {
        assertEquals("Unknown", EnvironmentInfo.osLabel(null));
        assertTrue(EnvironmentInfo.describe(null, ShellType.CMD, WORK_DIR).contains("- OS: Unknown"));
    }

    @Test
    void containsShellExecutionRules() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, WORK_DIR);
        assertTrue(text.contains("Shell execution rules:"));
        assertTrue(text.contains("Do not assume Unix commands such as pwd, ls, grep, etc."));
        assertTrue(text.contains("inspect the returned stderr/output"));
        assertTrue(text.contains("Do not blindly repeat the same or equivalent command."));
        assertTrue(text.contains("Prefer the smallest necessary verification command."));
    }

    @Test
    void workingDirectoryIsNormalizedToAbsolute() {
        String text = EnvironmentInfo.describe("Windows 11", ShellType.CMD, Path.of("relative/dir"));
        assertTrue(text.contains("- Working directory: "));
        // 相对路径会被转绝对路径，不可能再出现 "relative/dir"
        assertFalse(text.contains("relative/dir"));
    }
}
