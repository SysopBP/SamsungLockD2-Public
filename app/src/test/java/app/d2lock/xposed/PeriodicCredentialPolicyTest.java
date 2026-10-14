/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class PeriodicCredentialPolicyTest {
    private final long timeout = PeriodicCredentialPolicy.DEFAULT_TIMEOUT_MS;
    @Test public void requiresExplicitOptIn() {
        assertFalse(PeriodicCredentialPolicy.allowed(false, 0, false, false, timeout));
        assertTrue(PeriodicCredentialPolicy.allowed(true, 0, false, false, timeout));
    }
    @Test public void retainsStockForSecondaryAndManagedUsers() {
        assertFalse(PeriodicCredentialPolicy.allowed(true, 10, false, false, timeout));
        assertFalse(PeriodicCredentialPolicy.allowed(true, 0, true, false, timeout));
        assertFalse(PeriodicCredentialPolicy.allowed(true, 0, false, true, timeout));
    }
    @Test public void respectsNonDefaultPolicyTimeouts() {
        assertFalse(PeriodicCredentialPolicy.allowed(true, 0, false, false, 3600000));
        assertFalse(PeriodicCredentialPolicy.allowed(true, 0, false, false, 0));
        assertFalse(PeriodicCredentialPolicy.allowed(true, 0, false, false, Long.MAX_VALUE));
    }
}
