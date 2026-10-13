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
    @Test fun nestedSectionsAreAssignedWithoutLosingMediaOrClock() {
        assertEquals("guardian", SettingsNavigation.pageForSection("MEDIA"))
        assertEquals("appearance", SettingsNavigation.pageForSection("CLOCK & WEATHER"))
        assertEquals("appearance", SettingsNavigation.pageForSection("LOCK SCREEN EXTRAS"))
    }
    @Test fun newlyAddedSectionsStayReachable() {
        assertEquals("tools", SettingsNavigation.pageForSection("NEW FEATURE"))
        assertEquals("tools", SettingsNavigation.pageForSection("ABOUT & DATA"))
    }
}
