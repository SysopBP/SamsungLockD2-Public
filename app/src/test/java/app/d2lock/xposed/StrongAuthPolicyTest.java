/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class StrongAuthPolicyTest {
    @Test public void unknownValuesFallBackToStock() {
        assertEquals(0, PeriodicCredentialPolicy.normalise(0));
        assertEquals(0, PeriodicCredentialPolicy.normalise(72));
        assertEquals(0, PeriodicCredentialPolicy.normalise(-7));
        assertEquals(168, PeriodicCredentialPolicy.normalise(168));
        assertEquals(720, PeriodicCredentialPolicy.normalise(720));
        assertEquals(-1, PeriodicCredentialPolicy.normalise(-1));
    }
    @Test public void stockKeepsTheFrameworkTimeout() {
        assertEquals(0L, PeriodicCredentialPolicy.timeoutMs(0));
        assertEquals(0L, PeriodicCredentialPolicy.timeoutMs(72));
        assertTrue(PeriodicCredentialPolicy.isStock(0));
        assertFalse(PeriodicCredentialPolicy.isStock(168));
    }
    @Test public void supportedIntervalsMapToHours() {
        assertEquals(7L * 24 * 60 * 60 * 1000, PeriodicCredentialPolicy.timeoutMs(168));
        assertEquals(30L * 24 * 60 * 60 * 1000, PeriodicCredentialPolicy.timeoutMs(720));
        assertTrue(PeriodicCredentialPolicy.timeoutMs(-1) > 30L * 24 * 60 * 60 * 1000);
    }
    @Test public void labelsAreStable() {
        assertEquals("Stock \u00b7 72 hours", PeriodicCredentialPolicy.label(0));
        assertEquals("7 days", PeriodicCredentialPolicy.label(168));
        assertEquals("30 days", PeriodicCredentialPolicy.label(720));
        assertEquals("Never", PeriodicCredentialPolicy.label(-1));
    }
}
