package com.forgemind.core.tool;

import com.forgemind.model.ToolResult;

/**
 * Tool 结果渲染器：把 {@link ToolResult} 渲染为进入 LLM Context 的纯文本。
 *
 * <p>统一 context 输出限制（{@code outputLimit}）：只影响进入 LLM 的文本，
 * <b>绝不修改原始 ToolResult</b>。规则：</p>
 * <ul>
 *   <li>ToolResult 已 {@code truncated=true}（Tool 自身截断，如 shell）→ 不重复截断，保持原输出；</li>
 *   <li>ToolResult 未截断但正文超过 {@code outputLimit} → 截断进入 Context 的文本，
 *       追加 {@code [output truncated: context output limit]} 并置 {@code truncated: true}。</li>
 * </ul>
 */
public final class ToolResultRenderer {

    private ToolResultRenderer() {
    }

    public static String render(ToolResult result, String toolName, long outputLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool: ").append(toolName).append("]\n");
        sb.append("[success: ").append(result.success()).append("]\n");
        sb.append("[exitCode: ").append(result.exitCode()).append("]\n");

        String body;
        if (result.success()) {
            body = result.output() == null ? "(no output)" : result.output();
        } else {
            body = "ERROR: " + (result.error() == null ? "unknown error" : result.error());
        }

        boolean truncated = result.truncated();
        if (!truncated && outputLimit > 0 && body.length() > outputLimit) {
            body = body.substring(0, (int) outputLimit)
                    + "\n[output truncated: context output limit]";
            truncated = true;
        }

        sb.append("[truncated: ").append(truncated).append("]\n");
        sb.append(body);
        return sb.toString();
    }
}
