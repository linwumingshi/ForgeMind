package com.forgemind.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.permission.PermissionScope;
import com.forgemind.core.testutil.NamedTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryToolRegistryTest {

    @Test
    void registerAndFind() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new NamedTool("alpha", PermissionScope.READ));
        assertTrue(registry.find("alpha").isPresent());
        assertTrue(registry.find("nope").isEmpty());
    }

    @Test
    void duplicateRegistrationFailsClearly() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new NamedTool("dup", PermissionScope.READ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.register(new NamedTool("dup", PermissionScope.WRITE)));
        assertTrue(e.getMessage().contains("dup"));
        assertEquals(1, registry.size());
    }

    @Test
    void sizeCountsTools() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new NamedTool("a", PermissionScope.READ));
        registry.register(new NamedTool("b", PermissionScope.WRITE));
        assertEquals(2, registry.size());
    }

    @Test
    void allReturnsUnmodifiableSnapshot() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new NamedTool("first", PermissionScope.READ));
        Map<String, AgentTool> snapshot = registry.all();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", null));
        // 快照不反映后续注册
        registry.register(new NamedTool("second", PermissionScope.READ));
        assertEquals(1, snapshot.size());
        assertEquals(2, registry.size());
    }

    @Test
    void allIsSortedByName() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new NamedTool("zebra", PermissionScope.READ));
        registry.register(new NamedTool("apple", PermissionScope.READ));
        assertEquals(java.util.List.of("apple", "zebra"), java.util.List.copyOf(registry.all().keySet()));
    }

    @Test
    void nullToolRejected() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }
}
