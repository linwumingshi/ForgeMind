package com.forgemind.cli;

import com.forgemind.core.permission.PermissionAnswerer;
import com.forgemind.core.permission.PermissionRequest;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * 交互式权限应答：在终端提示 "Allow? [y/N]"，仅输入 y/Y 放行（安全默认 N）。
 * 与 REPL 共享同一个 {@link Scanner}，保证任务输入与权限询问不互相抢占缓冲。
 */
public final class InteractivePermissionAnswerer implements PermissionAnswerer {

    private final PrintStream out;
    private final Scanner scanner;

    public InteractivePermissionAnswerer(PrintStream out, Scanner scanner) {
        this.out = out;
        this.scanner = scanner;
    }

    @Override
    public boolean ask(PermissionRequest request) {
        out.println("Agent wants to execute:");
        out.println();
        out.println("    " + describe(request));
        out.println();
        out.print("Allow? [y/N] ");
        out.flush();
        String line = scanner.hasNextLine() ? scanner.nextLine() : "";
        return line.trim().equalsIgnoreCase("y");
    }

    private static String describe(PermissionRequest request) {
        if (request.detail() != null && !request.detail().isBlank()) {
            return request.detail();
        }
        return request.toolName();
    }
}
