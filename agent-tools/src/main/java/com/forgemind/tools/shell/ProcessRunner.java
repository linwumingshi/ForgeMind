package com.forgemind.tools.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程执行核心：启动、超时终止、Windows 进程树杀灭、stdout/stderr 双流有界捕获。
 *
 * <p>两个输出流必须同时读取（防止缓冲区写满导致进程阻塞）；每流累积到
 * {@code maxOutputBytes} 后停止累积、继续排空（drain）并置截断标记。</p>
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final long KILL_WAIT_MILLIS = 5000;

    private ProcessRunner() {
    }

    public static ShellResult run(List<String> command, Path workingDirectory,
                                  Duration timeout, long maxOutputBytes) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ShellResult(-1, "", "failed to start process: " + e.getMessage(),
                    false, false, false);
        }

        StreamGobbler out = new StreamGobbler(process.getInputStream(), maxOutputBytes);
        StreamGobbler err = new StreamGobbler(process.getErrorStream(), maxOutputBytes);
        Thread outThread = startDaemon("stdout-reader", out);
        Thread errThread = startDaemon("stderr-reader", err);

        boolean timedOut = false;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                log.warn("process timed out after {}ms, killing process tree: {}",
                        timeout.toMillis(), command.get(command.size() - 1));
                killProcessTree(process);
                process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
            killProcessTree(process);
        }

        joinSilently(outThread);
        joinSilently(errThread);

        int exitCode = timedOut ? -1 : process.exitValue();
        return new ShellResult(exitCode, out.content(), err.content(),
                timedOut, out.truncated(), err.truncated());
    }

    private static Thread startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinSilently(Thread thread) {
        try {
            thread.join(KILL_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 强制终止；Windows 上再 taskkill /F /T 杀掉整个进程树。 */
    private static void killProcessTree(Process process) {
        process.destroyForcibly();
        if (!isWindows()) {
            return;
        }
        try {
            Process killer = new ProcessBuilder(
                    "taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                    .redirectErrorStream(true)
                    .start();
            killer.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (IOException | InterruptedException e) {
            log.warn("failed to kill process tree: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 输出字节解码：优先严格 UTF-8；解码失败（如中文 Windows 下 cmd 按 ANSI
     * 代码页 GBK 输出）则回退到原生编码（{@code sun.jnu.encoding}）。
     */
    private static String decodeUtf8WithFallback(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            Charset fallback = defaultNativeCharset();
            return new String(bytes, fallback);
        }
    }

    private static Charset defaultNativeCharset() {
        String jnu = System.getProperty("sun.jnu.encoding");
        if (jnu != null) {
            try {
                return Charset.forName(jnu);
            } catch (RuntimeException ignored) {
                // 未知编码名，走默认
            }
        }
        return Charset.defaultCharset();
    }

    /** 有界输出捕获器：读满上限后停止累积、继续排空，避免进程因管道阻塞。 */
    private static final class StreamGobbler implements Runnable {
        private final InputStream in;
        private final long maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        StreamGobbler(InputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (buffer.size() < maxBytes) {
                        int room = (int) Math.min(n, maxBytes - buffer.size());
                        buffer.write(chunk, 0, room);
                        if (room < n) {
                            truncated = true;
                        }
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // 流被强制关闭（进程被杀）属正常
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }

        String content() {
            return decodeUtf8WithFallback(buffer.toByteArray());
        }

        boolean truncated() {
            return truncated;
        }
    }
}
