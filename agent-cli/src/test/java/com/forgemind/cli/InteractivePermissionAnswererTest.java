package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgemind.core.permission.PermissionRequest;
import com.forgemind.core.permission.PermissionScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

class InteractivePermissionAnswererTest {

    private InteractivePermissionAnswerer answerer(String input) {
        Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return new InteractivePermissionAnswerer(
                new PrintStream(new ByteArrayOutputStream()), scanner);
    }

    private static PermissionRequest request(String detail) {
        return PermissionRequest.of(PermissionScope.SHELL, "shell", "run command", detail);
    }

    @Test
    void lowercaseYAllows() {
        assertTrue(answerer("y\n").ask(request("mvn test")));
    }

    @Test
    void uppercaseYAllows() {
        assertTrue(answerer("Y\n").ask(request("mvn test")));
    }

    @Test
    void nDenies() {
        assertFalse(answerer("n\n").ask(request("mvn test")));
    }

    @Test
    void emptyInputDeniesByDefault() {
        assertFalse(answerer("\n").ask(request("mvn test")));
    }

    @Test
    void eofDeniesByDefault() {
        assertFalse(answerer("").ask(request("mvn test")));
    }

    @Test
    void promptsWithDetailOrToolName() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Scanner scanner = new Scanner(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        InteractivePermissionAnswerer answerer =
                new InteractivePermissionAnswerer(new PrintStream(buffer, true, StandardCharsets.UTF_8), scanner);
        answerer.ask(request("mvn test"));
        String prompt = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(prompt.contains("mvn test"));
        assertTrue(prompt.contains("Allow? [y/N]"));
    }
}
