package app.d2lock

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PatternUi
import app.d2lock.security.PinStore
import app.d2lock.terminal.GuardianTerminalActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AuditRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val credential get() = File(context.noBackupFilesDir, "d2-pin.json")
    @Before fun prepare() {
        TestDevice.wake()
        Prefs.setEnabled(context, false)
        Prefs.setKiosk(context, false)
        assertFalse("Clean credential fixture required", credential.exists())
        PinStore(context).create("246810".toCharArray())
    }
    @After fun cleanup() {
        Prefs.setEnabled(context, false)
        Prefs.setKiosk(context, false)
        credential.delete()
        File(credential.path + ".bak").delete()
    }
    @Test fun realRequestUpgradesPreviewAndCannotBeDowngraded() {
        ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java).putExtra("preview", true)).use { scenario ->
            fun deliver(preview: Boolean) {
                scenario.onActivity { activity ->
                    val method = LockScreenActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    method.isAccessible = true
                    method.invoke(activity, Intent(context, LockScreenActivity::class.java).putExtra("preview", preview))
                }
                instrumentation.waitForIdleSync()
            }
            deliver(false)
            SystemClock.sleep(500)
            scenario.onActivity { assertFalse(it.intent.getBooleanExtra("preview", false)) }
            deliver(true)
            scenario.onActivity { assertFalse("A real lock cannot become dismissible preview", it.intent.getBooleanExtra("preview", false)) }
        }
    }
    @Test fun cancelledPatternNotifiesOwnerExactlyOnce() {
        ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java).putExtra("preview", true)).use { scenario ->
            val cancelled = java.util.concurrent.atomic.AtomicInteger(0)
            scenario.onActivity { activity ->
                val dialog = PatternUi.show(activity, success = { fail("Cancelled dialog must not authenticate") }, cancel = { cancelled.incrementAndGet() })
                dialog.cancel()
                dialog.cancel()
            }
            // Dialog cancellation dispatches its listener through the main message queue.
            instrumentation.waitForIdleSync()
            assertEquals(1, cancelled.get())
        }
    }
    @Test fun terminalRequiresPinAndProtectsWindow() {
        ActivityScenario.launch<GuardianTerminalActivity>(Intent(context, GuardianTerminalActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val authorized = GuardianTerminalActivity::class.java.getDeclaredField("authorized").apply { isAccessible = true }
                assertFalse(authorized.getBoolean(activity))
            }
        }
    }
    @Test fun malformedImportDoesNotPartiallyWrite() {
        Prefs.setWallpaperDim(context, 30)
        assertTrue(runCatching { Prefs.importSettings(context, "{\"wallpaper_dim\":true,\"clock_scale\":90}") }.isFailure)
        assertEquals(30, Prefs.wallpaperDim(context))
    }
}
