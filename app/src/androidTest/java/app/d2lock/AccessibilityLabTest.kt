package app.d2lock

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.accessibility.D2AccessibilityService
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityLabTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun serviceMetadataDoesNotRequestScreenAccessOrGestureInjection() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val info = manager.installedAccessibilityServiceList.first {
            it.resolveInfo.serviceInfo.name == D2AccessibilityService::class.java.name
        }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.resolveInfo.serviceInfo.permission)
        assertEquals(0, info.eventTypes)
        assertEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        assertEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    }

    @Test fun disabledRouteDoesNotRequestScreenLockAndUnboundManualTestCannotAuthenticate() {
        assumeFalse(D2AccessibilityService.isConnected())
        val preferences = context.getSharedPreferences("d2_accessibility_lab", Context.MODE_PRIVATE)
        val before = Prefs.unlockMethod(context)
        val previousRoute = D2AccessibilityService.testRouteEnabled(context)
        val timestamp = preferences.getLong("request_time", 0L)
        try {
            D2AccessibilityService.setTestRoute(context, false)
            assertFalse(D2AccessibilityService.requestScreenOff(context))
            assertEquals(timestamp, preferences.getLong("request_time", 0L))
            assertFalse(D2AccessibilityService.requestScreenOff(context, manualTest = true))
            assertEquals(before, Prefs.unlockMethod(context))
            assertFalse(D2AccessibilityService.testRouteEnabled(context))
        } finally {
            D2AccessibilityService.setTestRoute(context, previousRoute)
        }
    }
}
