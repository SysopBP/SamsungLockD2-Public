/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class StrongAuthDiagnosticsTest {
    @Test public void separatesPinPatternAndUnknownCredentialTypes() {
        assertEquals("Samsung PIN", StrongAuthDiagnostics.credential(3));
        assertEquals("Samsung pattern", StrongAuthDiagnostics.credential(1));
        assertTrue(StrongAuthDiagnostics.credential(999).contains("unsupported"));
    }
    @Test public void timeoutIsDistinctFromBootLockdownAndUnknownOemFlags() {
        assertEquals("Unavailable", StrongAuthDiagnostics.reasons(-1));
        assertFalse(StrongAuthDiagnostics.reasons(0).contains("timeout"));
        assertTrue(StrongAuthDiagnostics.reasons(1).contains("after boot"));
        assertTrue(StrongAuthDiagnostics.reasons(16).contains("primary-auth timeout"));
        assertTrue(StrongAuthDiagnostics.reasons(32).contains("lockdown"));
        String combined = StrongAuthDiagnostics.reasons(1 | 16 | 0x4000);
        assertTrue(combined.contains("after boot"));
        assertTrue(combined.contains("primary-auth timeout"));
        assertTrue(combined.contains("unknown/OEM flags 0x4000"));
    }
    @Test public void rejectsStalePidBootSchemaAndClockMarkers() {
        assertTrue(StrongAuthDiagnostics.current(2, 123, 123, 5, 5, 100, 200));
        assertFalse(StrongAuthDiagnostics.current(1, 123, 123, 5, 5, 100, 200));
        assertFalse(StrongAuthDiagnostics.current(2, 122, 123, 5, 5, 100, 200));
        assertFalse(StrongAuthDiagnostics.current(2, 123, 123, 4, 5, 100, 200));
        assertFalse(StrongAuthDiagnostics.current(2, 123, 123, -1, -1, 100, 200));
        assertFalse(StrongAuthDiagnostics.current(2, 123, 123, 5, 5, 300, 200));
        assertFalse(StrongAuthDiagnostics.current(2, 123, 123, 5, 5, -1, 200));
    }
}
