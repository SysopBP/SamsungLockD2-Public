package app.d2lock

import android.app.ActivityManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import org.hamcrest.Matchers.sameInstance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.root.RootKiosk
import app.d2lock.security.PinStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RootKioskTest {
    @Before fun prepareEmulator() = TestDevice.wake()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(ActivityManager::class.java)
    private fun waitFor(message: String, timeout: Long = 20000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val ok = condition()
        assertTrue("$message (${RootKiosk.diagnostic}) ${if (!ok) TestDevice.focusDiagnostic() else ""}", ok)
    }
    private fun connect(dropHeartbeats: AtomicBoolean = AtomicBoolean(false)): RootKiosk.Channel {
        // Emulator-only root, reached through the test harness's shell identity.
        val command = "su 0 env CLASSPATH=${context.applicationInfo.sourceDir} /system/bin/app_process /system/bin app.d2lock.root.KioskBridge 0"
        val pipes = instrumentation.uiAutomation.executeShellCommandRw(command)
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
        val filtered = object : FilterOutputStream(output) {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!dropHeartbeats.get()) out.write(buffer, offset, length)
            }
        }
        return RootKiosk.Channel(ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader(), filtered.bufferedWriter()) {
            runCatching { output.close() }
        }
    }
    private fun fixture(body: () -> Unit) {
        val file = File(context.noBackupFilesDir, "d2-pin.json")
        assertFalse("Clean emulator required", file.exists())
        PinStore(context).create("246810".toCharArray())
        Prefs.setKiosk(context, true)
        try { body() } finally {
            Prefs.setKiosk(context, false)
            RootKiosk.testConnect = null
            file.delete(); File(file.path + ".bak").delete()
        }
    }
    private fun cleanup(scenario: ActivityScenario<LockScreenActivity>) {
        if (scenario.state != Lifecycle.State.DESTROYED) {
            val done = CountDownLatch(1)
            scenario.onActivity { RootKiosk.unlock(it) { done.countDown() } }
            assertTrue(done.await(15, TimeUnit.SECONDS))
        }
        scenario.close()
    }
    private fun launch(): ActivityScenario<LockScreenActivity> {
        val scenario = ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java))
        var task = -1
        scenario.onActivity { task = it.taskId }
        TestDevice.focusTask(task)
        return scenario
    }
    @Test fun kioskBlocksHomeRecentsWrongPinAndAllowsCorrectPin() = fixture {
        RootKiosk.testConnect = { connect() }
        val scenario = launch()
        try {
            waitFor("Must enter full LOCKED mode, never PINNED") { manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED }
            for (key in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)) {
                instrumentation.sendKeyDownUpSync(key)
                SystemClock.sleep(500)
                assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, manager.lockTaskModeState)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
            }
            scenario.recreate()
            assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, manager.lockTaskModeState)
            var task = -1
            var decor: android.view.View? = null
            scenario.onActivity { task = it.taskId; decor = it.window.decorView }
            TestDevice.focusTask(task)
            SystemClock.sleep(500)
            onView(withText("PIN")).inRoot(withDecorView(sameInstance(decor))).perform(click())
            onView(withHint("6-digit D2 PIN")).inRoot(isDialog()).perform(typeText("111111"), closeSoftKeyboard())
            onView(withText("Unlock")).inRoot(isDialog()).perform(click())
            waitFor("Wrong PIN must visibly be rejected") { runCatching {
                onView(withText("PIN incorrect.")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                true
            }.getOrDefault(false) }
            assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, manager.lockTaskModeState)
            onView(withHint("6-digit D2 PIN")).inRoot(isDialog()).perform(replaceText("246810"), closeSoftKeyboard())
            onView(withText("Unlock")).inRoot(isDialog()).perform(click())
            waitFor("Correct PIN must restore navigation", 25000) { scenario.state == Lifecycle.State.DESTROYED && manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE }
        } finally { cleanup(scenario) }
    }
    @Test fun lostAppConnectionReleasesKiosk() = fixture {
        var bridge: RootKiosk.Channel? = null
        RootKiosk.testConnect = { connect().also { bridge = it } }
        val scenario = launch()
        try {
            waitFor("Kiosk must start") { manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED }
            bridge!!.close()
            waitFor("App death/EOF must release kiosk") { manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE }
        } finally { cleanup(scenario) }
    }
    @Test fun rootDeniedDoesNotClaimKiosk() = fixture {
        RootKiosk.testConnect = { throw SecurityException("Root denied for test") }
        val scenario = launch()
        try {
            SystemClock.sleep(1500)
            onView(withText("Kiosk unavailable: Root denied for test")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, manager.lockTaskModeState)
        } finally { cleanup(scenario) }
    }
    @Test fun missingUiHeartbeatsTriggersIndependentRecovery() = fixture {
        val drop = AtomicBoolean(false)
        RootKiosk.testConnect = { connect(drop) }
        val scenario = launch()
        try {
            waitFor("Kiosk must start") { manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED }
            drop.set(true)
            waitFor("Independent root watchdog must release without app heartbeats", 30000) {
                manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            }
        } finally { cleanup(scenario) }
    }
}
