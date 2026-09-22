package app.d2lock

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import org.hamcrest.Matchers.sameInstance
import androidx.lifecycle.Lifecycle
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PinStore
import app.d2lock.widget.DoubleTap
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class D2SecurityTest {
    @Before fun prepareEmulator() = TestDevice.wake()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pinVerificationChangeAndPersistentThrottle() {
        val folder = File(context.cacheDir, "pin-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        var time = 100_000L
        try {
            val store = PinStore(isolated) { time }
            assertFalse(store.configured())
            store.create("246810".toCharArray())
            assertTrue(store.verify("246810".toCharArray()))
            repeat(5) { assertFalse(store.verify("111111".toCharArray())) }
            val reopened = PinStore(isolated) { time }
            assertTrue(reopened.remainingMillis() > 0)
            assertFalse(reopened.verify("246810".toCharArray()))
            time += 30_001
            assertTrue(reopened.change("246810".toCharArray(), "135790".toCharArray()))
            assertFalse(reopened.verify("246810".toCharArray()))
            assertTrue(reopened.verify("135790".toCharArray()))
            val disk = File(folder, "d2-pin.json").readText()
            assertFalse(disk.contains("135790"))
            assertFalse(disk.contains("246810"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun doubleTapRequiresTwoCloseTapsAndDoesNotCarryOver() {
        val taps = DoubleTap()
        assertFalse(taps.tap(1000))
        assertFalse(taps.tap(2200))
        assertTrue(taps.tap(2450))
        assertFalse(taps.tap(2600))
        assertFalse(taps.tap(10)) // reboot/elapsed clock reset cannot count as a second tap
        assertTrue(taps.tap(250))
    }
    @Test fun configuredPinProtectsSettingsAndLockSurvivesRecreation() {
        val credential = File(context.noBackupFilesDir, "d2-pin.json")
        assertFalse("Clean emulator required", credential.exists())
        PinStore(context).create("246810".toCharArray())
        context.getSharedPreferences("lock_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun hasSettings(v: View): Boolean {
                        if (v is TextView && v.text.toString() == "Change D2 PIN") return true
                        if (v is ViewGroup) return (0 until v.childCount).any { hasSettings(v.getChildAt(it)) }
                        return false
                    }
                    assertFalse("Settings must not be exposed before PIN verification", hasSettings(activity.window.decorView))
                }
            }
            ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java)).use { scenario ->
                var task = -1
                scenario.onActivity { task = it.taskId }
                TestDevice.focusTask(task)
                val focusDeadline = SystemClock.elapsedRealtime() + 15000
                var focused = false
                while (!focused && SystemClock.elapsedRealtime() < focusDeadline) {
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    if (!focused) SystemClock.sleep(100)
                }
                assertTrue("D2 window must receive focus before injecting Back: ${if (!focused) TestDevice.focusDiagnostic() else ""}", focused)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                onView(withId(app.d2lock.R.id.d2_pin_prompt)).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                scenario.recreate()
                TestDevice.focusTask(task)
                SystemClock.sleep(500)
                scenario.onActivity { assertFalse(it.isFinishing) }
                var decor: View? = null
                scenario.onActivity { decor = it.window.decorView }
                onView(withText("PIN")).inRoot(withDecorView(sameInstance(decor))).perform(click())
                onView(withId(app.d2lock.R.id.d2_pin_input)).inRoot(isDialog()).perform(typeText("246810"), closeSoftKeyboard())
                onView(withText("Unlock")).inRoot(isDialog()).perform(click())
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                assertEquals("Correct app PIN must close the lock screen", Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { credential.delete(); File(credential.path + ".bak").delete() }
    }
}
