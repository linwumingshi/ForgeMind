package com.forgemind.tools.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/** Git 测试辅助：初始化真实仓库、执行 git 命令。 */
final class GitTestSupport {

    private GitTestSupport() {
    }

    static void assumeGitAvailable() {
        Assumptions.assumeTrue(gitAvailable(), "git not available in this environment");
    }

    static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        Assumptions.assumeTrue(code == 0, "git " + String.join(" ", args) + " failed:\n" + out);
    }

    /** 初始化仓库并配置 user/quotepath（中文文件名原样输出）。 */
    static void initRepo(Path dir) throws Exception {
        git(dir, "init", "-b", "main");
        git(dir, "config", "user.email", "test@forgemind.local");
        git(dir, "config", "user.name", "ForgeMind Test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.quotepath", "false");
    }

    static void commitAll(Path dir, String message) throws Exception {
        git(dir, "add", "-A");
        git(dir, "commit", "-m", message);
    }

    /** 最近一次 commit 的完整 message。 */
    static String lastCommitMessage(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "-C", dir.toString(), "log", "-1", "--pretty=%B").start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.trim();
    }

    /** 当前 commit 数量。 */
    static int commitCount(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "-C", dir.toString(), "rev-list", "--count", "HEAD").start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return Integer.parseInt(out.trim());
    }

    /** 最近一次 commit hash。 */
    static String lastCommitHash(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "--short", "HEAD").start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.trim();
    }
}
