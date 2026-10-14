/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class SchedulerSignatureTest {
    @Test public void supportedLayoutsHaveUnambiguousUserPosition() {
        assertTrue(SchedulerSignature.supported(new String[]{"long", "int"}));
        assertTrue(SchedulerSignature.supported(new String[]{"long", "long", "int"}));
        assertFalse(SchedulerSignature.supported(new String[]{"int", "long"}));
        assertFalse(SchedulerSignature.supported(new String[]{"java.lang.Long", "int"}));
        assertFalse(SchedulerSignature.supported(new String[]{"long", "int", "int"}));
        assertFalse(SchedulerSignature.supported(new String[]{"int"}));
    }
}
