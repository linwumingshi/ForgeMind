package com.forgemind.core.loop;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 相同 shell 命令连续失败护栏（只提示、不阻断）。
 *
 * <p>目的：防止 LLM 在同一命令反复失败后仍无脑重试，浪费迭代预算。
 * 仅对 shell tool 生效；不同命令独立计数；命令成功即清零。
 * 阈值：第 1 次失败不提示，第 2 次弱提示，第 3 次起强提示。</p>
 */
public final class CommandFailureTracker {

    /** 弱提示：同一命令第 2 次连续失败。 */
    private static final String WEAK_HINT =
            "This command has failed repeatedly. Do not blindly retry the same command: "
                    + "first inspect the previous stderr/output to identify the actual failure cause. "
                    + "If this is a compile/run issue, verify that compilation succeeded, "
                    + "the class/output file exists, and the package/classpath is correct.";

    /** 强提示：同一命令第 3 次及以上连续失败。 */
    private static final String STRONG_HINT =
            "The same command has failed multiple times. Do not retry it again without changing "
                    + "the underlying approach. Inspect the relevant files, the compilation/output "
                    + "results, the package/classpath, or use a different diagnostic command "
                    + "before trying again.";

    private final Map<String, Integer> consecutiveFailures = new HashMap<>();

    /** 记录一次失败，返回该命令当前的连续失败次数（含本次）。 */
    public int recordFailure(String command) {
        String key = normalize(command);
        return consecutiveFailures.merge(key, 1, Integer::sum);
    }

    /** 记录一次成功：清零该命令的连续失败计数。 */
    public void recordSuccess(String command) {
        consecutiveFailures.remove(normalize(command));
    }

    /** 根据连续失败次数生成提示：1 次及以下返回 null；2 次弱提示；3 次起强提示。 */
    public static String hintFor(int consecutive) {
        if (consecutive <= 1) {
            return null;
        }
        return consecutive == 2 ? WEAK_HINT : STRONG_HINT;
    }

    /**
     * 命令规范化：仅合并"等义变体"，避免过度归一误判。
     * trim → 转小写 → 去尾部 {@code 2>&1}（可重复）→ 去开头 {@code cmd /c}（含引号变体）。
     */
    static String normalize(String command) {
        if (command == null) {
            return "";
        }
        String c = command.trim().toLowerCase(Locale.ROOT);
        while (c.endsWith("2>&1")) {
            c = c.substring(0, c.length() - 4).trim();
        }
        if (c.startsWith("cmd /c ")) {
            c = c.substring("cmd /c ".length()).trim();
            // 引号变体：cmd /c "java demo" 与 cmd /c java demo 归一等义
            if (c.startsWith("\"")) {
                c = c.substring(1);
            }
            if (c.endsWith("\"")) {
                c = c.substring(0, c.length() - 1);
            }
            c = c.trim();
        }
        return c;
    }
}
