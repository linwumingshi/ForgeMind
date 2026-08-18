# m5-e2e-workspace

真实 LLM 端到端验收沙箱（也可作为手工演示工作区）。

仅包含少量示例文件，Agent 的 `--working-dir` 指向本目录时，
WorkspaceAccess 会保证所有文件操作被限制在本目录内。

- `hello.txt` — 简单文本
- `src/Test.java` — 示例 Java 源码
