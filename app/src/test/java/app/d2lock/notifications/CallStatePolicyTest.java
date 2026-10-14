/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.notifications;
import org.junit.Test;
import static org.junit.Assert.*;
public class CallStatePolicyTest {
    @Test public void ringingMayBeNonOngoingAndHaveNoPresentationControls() {
        assertTrue(CallStatePolicy.notificationActive(true, true, false, true, false));
        assertTrue(CallStatePolicy.notificationActive(true, true, false, false, true));
        assertTrue(CallStatePolicy.active(false, true));
    }
    @Test public void retainsTelecomCallsWithoutNotifications() {
        assertTrue(CallStatePolicy.active(true, false));
        assertFalse(CallStatePolicy.active(false, false));
    }
    @Test public void rejectsUntrustedNonCallAndOrdinaryMissedCallNotifications() {
        assertFalse(CallStatePolicy.notificationActive(false, true, true, true, true));
        assertFalse(CallStatePolicy.notificationActive(true, false, true, true, true));
        assertFalse(CallStatePolicy.notificationActive(true, true, false, false, false));
        assertTrue(CallStatePolicy.notificationActive(true, true, true, false, false));
    }
}
