package app.d2lock.root;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded output collection that keeps draining even after the report limit. */
public final class ShellRunner {
    public static final int OUTPUT_LIMIT = 256 * 1024;

    public static final class Result {
        public final String output;
        public final int exitCode;
        public final boolean timedOut;
        public final boolean truncated;

        Result(String output, int exitCode, boolean timedOut, boolean truncated) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.truncated = truncated;
        }

        public boolean succeeded() { return !timedOut && exitCode == 0; }
    }

    public static Result run(List<String> arguments, long timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int remaining = OUTPUT_LIMIT - bytes.size();
                        if (count > remaining) truncated[0] = true;
                        if (remaining > 0) bytes.write(buffer, 0, Math.min(count, remaining));
                    }
                }
            } catch (Exception ignored) {
                // Closing a cancelled or timed-out process also closes the report stream.
            }
        }, "d2-shell-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean complete = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!complete) process.destroyForcibly();
            reader.join(1000);
            if (reader.isAlive()) process.getInputStream().close();
            synchronized (bytes) {
                return new Result(new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                        complete ? process.exitValue() : -1, !complete, truncated[0]);
            }
        } finally {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (Exception ignored) { }
        }
    }

    private ShellRunner() { }
}
