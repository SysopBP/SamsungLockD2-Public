package app.d2lock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    @Test fun resettingAppearancePreservesSecurityPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = Prefs.kiosk(context)
        Appearance.set(context, "mode", 3)
        Appearance.set(context, "bar_width", -1)
        Appearance.set(context, "bar_gap", 999)
        assertEquals(65, Appearance.barWidth(context))
        assertEquals(72, Appearance.barGap(context))
        Appearance.reset(context)
        assertEquals(before, Prefs.kiosk(context))
        assertEquals(90, Appearance.barWidth(context))
        assertEquals(2, Appearance.mode(context))
    }
}
