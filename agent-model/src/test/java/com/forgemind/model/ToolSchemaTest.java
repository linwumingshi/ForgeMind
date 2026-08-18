package com.forgemind.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaTest {

    @Test
    void keepsPropertiesAndRequired() {
        ToolSchema schema = ToolSchema.of(
                Map.of("path", new ToolParameter("string", "file path")),
                List.of("path"));
        assertEquals(1, schema.properties().size());
        assertEquals(List.of("path"), schema.required());
    }

    @Test
    void requiredMustBeDeclaredInProperties() {
        assertThrows(IllegalArgumentException.class, () -> ToolSchema.of(Map.of(), List.of("path")));
    }

    @Test
    void nullArgumentsBecomeEmpty() {
        ToolSchema schema = new ToolSchema(null, null);
        assertTrue(schema.properties().isEmpty());
        assertTrue(schema.required().isEmpty());
    }

    @Test
    void defensivelyCopied() {
        Map<String, ToolParameter> mutableProps = new HashMap<>();
        mutableProps.put("a", new ToolParameter("string", "a"));
        List<String> mutableRequired = new ArrayList<>();
        mutableRequired.add("a");
        ToolSchema schema = new ToolSchema(mutableProps, mutableRequired);
        mutableProps.clear();
        mutableRequired.clear();
        assertEquals(1, schema.properties().size());
        assertEquals(List.of("a"), schema.required());
        assertThrows(UnsupportedOperationException.class, () -> schema.required().add("b"));
    }

    @Test
    void typeIsObject() {
        assertEquals("object", ToolSchema.TYPE_OBJECT);
    }
}
