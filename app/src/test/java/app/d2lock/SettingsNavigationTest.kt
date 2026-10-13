package app.d2lock

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test fun securityAndRecoveryRemainInTheirIntendedDestinations() {
        assertEquals("security", SettingsNavigation.pageForSection("FINGERPRINT CENTER"))
        assertEquals("guardian", SettingsNavigation.pageForSection("GUARDIAN & KIOSK"))
        assertEquals("tools", SettingsNavigation.pageForSection("D2 SRP"))
        assertEquals("tools", SettingsNavigation.pageForSection("ADB / USB RECOVERY"))
        assertEquals("appearance", SettingsNavigation.pageForSection("FLOATING BAR"))
    }
    @Test fun newlyAddedSectionsStayReachable() {
        assertEquals("tools", SettingsNavigation.pageForSection("NEW FEATURE"))
        assertEquals("tools", SettingsNavigation.pageForSection("ABOUT & DATA"))
    }
}
