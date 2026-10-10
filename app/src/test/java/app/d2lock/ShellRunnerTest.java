package app.d2lock;

import app.d2lock.root.ShellRunner;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShellRunnerTest {
    @Test public void drainsBeyondPipeAndReportLimits() throws Exception {
        ShellRunner.Result result = ShellRunner.run(Arrays.asList("sh", "-c", "head -c 524288 /dev/zero"), 5);
        assertTrue(result.succeeded());
        assertTrue(result.truncated);
        assertEquals(ShellRunner.OUTPUT_LIMIT, result.output.length());
    }
    @Test public void limitsHangingCommandsAndReportsFailures() throws Exception {
        ShellRunner.Result timeout = ShellRunner.run(Arrays.asList("sh", "-c", "exec sleep 10"), 1);
        assertTrue(timeout.timedOut);
        assertFalse(timeout.succeeded());
        ShellRunner.Result failure = ShellRunner.run(Arrays.asList("sh", "-c", "printf error >&2; exit 7"), 5);
        assertEquals(7, failure.exitCode);
        assertEquals("error", failure.output);
    }
}
