package app.d2lock.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherBridgePolicyTest {
    @Test fun resumeDoesNotRelockAfterAuthentication() {
        assertFalse(LauncherBridgePolicy.canLock(true, true, false, false, false, false))
        assertTrue(LauncherBridgePolicy.canLock(true, true, false, false, false, true))
    }
    @Test fun explicitLockRequiresEnabledAndConfiguredD2() {
        assertTrue(LauncherBridgePolicy.canLock(true, true, false, false, true, false))
        assertFalse(LauncherBridgePolicy.canLock(false, true, false, false, true, false))
        assertFalse(LauncherBridgePolicy.canLock(true, false, false, false, true, false))
    }
    @Test fun callsAndAuthenticationAlwaysWin() {
        for (force in listOf(false, true)) {
            assertFalse(LauncherBridgePolicy.canLock(true, true, true, false, force, true))
            assertFalse(LauncherBridgePolicy.canLock(true, true, false, true, force, true))
        }
    }
}
