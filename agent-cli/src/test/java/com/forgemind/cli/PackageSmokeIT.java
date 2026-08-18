package com.forgemind.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * 打包后集成测试（failsafe，integration-test 阶段执行）：
 * 真正执行构建出的 fat jar（--version / --help），验证打包可用。
 */
class PackageSmokeIT {

    private Path jar() {
        return Path.of("").toAbsolutePath().resolve("target/forgemind.jar");
    }

    private Path cmdScript() {
        return Path.of("").toAbsolutePath().getParent().resolve("forgemind.cmd");
    }

    @Test
    void fatJarExists() {
        assertTrue(Files.exists(jar()), "forgemind.jar 必须存在（先 mvn package）");
    }

    @Test
    void manifestDeclaresMainClass() throws IOException {
        try (JarFile jar = new JarFile(jar().toFile())) {
            String manifest = jar.getManifest().getMainAttributes().getValue("Main-Class");
            assertEquals("com.forgemind.cli.ForgemindCommand", manifest);
        }
    }

    @Test
    void jarRunsVersion() throws Exception {
        RunResult result = run("--version");
        assertEquals(0, result.exitCode(), "java -jar forgemind.jar --version 应正常退出");
        assertTrue(result.output.contains("ForgeMind"), "版本输出应包含 ForgeMind");
    }

    @Test
    void jarRunsHelp() throws Exception {
        RunResult result = run("--help");
        assertEquals(0, result.exitCode(), "java -jar forgemind.jar --help 应正常退出");
        assertTrue(result.output.contains("Usage:"), "help 输出应包含 Usage");
        assertTrue(result.output.contains("--working-dir"));
        assertTrue(result.output.contains("--yes"));
        assertTrue(result.output.contains("--config"));
    }

    @Test
    void cmdScriptExists() {
        assertTrue(Files.exists(cmdScript()), "项目根目录应存在 forgemind.cmd");
    }

    private RunResult run(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaBin(), "-jar", jar().toString());
        pb.command().addAll(java.util.List.of(args));
        Process process = pb.start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "进程应在超时内退出");
        return new RunResult(process.exitValue(),
                new String(out, StandardCharsets.UTF_8) + new String(err, StandardCharsets.UTF_8));
    }

    private static String javaBin() {
        String javaHome = System.getProperty("java.home");
        Path bin = Path.of(javaHome, "bin", "java" + (isWindows() ? ".exe" : ""));
        return bin.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record RunResult(int exitCode, String output) {
    }
}
