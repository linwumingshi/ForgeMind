package com.forgemind.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forgemind.core.exception.ConfigException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ToolLimitsTest {

    @Test
    void defaultsAreSane() {
        ToolLimits limits = ToolLimits.defaults();
        assertEquals(500, limits.listFilesMaxEntries());
        assertEquals(3, limits.listFilesMaxDepth());
        assertEquals(1024L * 1024, limits.readFileMaxBytes());
        assertEquals(64L * 1024, limits.editFileOldTextMaxBytes());
        assertEquals(200, limits.searchMaxResults());
        assertEquals(1024L * 1024, limits.searchMaxFileBytes());
        assertEquals(64L * 1024, limits.outputLimit());
        assertEquals(Duration.ofSeconds(60), limits.shellTimeout());
        assertEquals(ShellType.CMD, limits.shellType());
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(ConfigException.class, () -> ToolLimits.defaults().withListFilesMaxEntries(0));
        assertThrows(ConfigException.class, () -> ToolLimits.defaults().withReadFileMaxBytes(-1));
        assertThrows(ConfigException.class, () -> ToolLimits.defaults().withOutputLimit(0));
        assertThrows(ConfigException.class, () -> ToolLimits.defaults().withShellTimeout(Duration.ZERO));
    }

    @Test
    void withersAreImmutable() {
        ToolLimits base = ToolLimits.defaults();
        ToolLimits changed = base.withShellTimeout(Duration.ofMillis(800)).withShellType(ShellType.POWERSHELL);
        assertNotSame(base, changed);
        assertEquals(Duration.ofSeconds(60), base.shellTimeout());
        assertEquals(ShellType.CMD, base.shellType());
        assertEquals(Duration.ofMillis(800), changed.shellTimeout());
        assertEquals(ShellType.POWERSHELL, changed.shellType());
    }
}
