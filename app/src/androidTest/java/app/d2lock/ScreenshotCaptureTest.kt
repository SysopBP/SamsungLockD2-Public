package app.d2lock

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.root.RootKiosk
import app.d2lock.security.PinStore
import app.d2lock.Prefs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in emulator documentation capture. No screenshot bypass ships in the app APK. */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Screenshot state did not become ready", condition())
    }
    private fun <T : Activity> capture(scenario: ActivityScenario<T>, name: String) {
        scenario.onActivity { activity ->
            // Only this separately installed instrumentation APK clears secure flags.
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val dialog = activity.javaClass.getDeclaredField("pinDialog").apply { isAccessible = true }.get(activity) as? AlertDialog
            dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        // AGP uninstalls the app after tests, deleting its external-files directory.
        // Write through the instrumentation shell so captures survive that cleanup.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/d2-screenshots")).use { it.readBytes() }
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/d2-screenshots/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        bitmap.recycle()
    }
    @Test fun captureDocumentation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        TestDevice.wake()
        val pinFile = File(context.noBackupFilesDir, "d2-pin.json")
        assertFalse("Use a fresh emulator", pinFile.exists())
        context.getSharedPreferences("lock_preferences", 0).edit().clear().commit()
        PinStore(context).create("246810".toCharArray())
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cmd notification allow_listener app.d2lock/app.d2lock.notifications.LockNotificationListener"
        )).use { it.readBytes() }
        val mediaSession = MediaSession(context, "D2ScreenshotMedia").apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Demo track")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Media preview")
                .build())
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            isActive = true
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withHint("6-digit D2 PIN")).inRoot(isDialog()).perform(typeText("246810"), closeSoftKeyboard())
                onView(withText("Unlock")).inRoot(isDialog()).perform(click())
                waitUntil { runCatching { onView(withText("Change D2 PIN")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed())); true }.getOrDefault(false) }
                capture(scenario, "01-settings")
                onView(withText("When D2 is locked")).perform(scrollTo())
                capture(scenario, "05-customization")
            }
            ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java).putExtra("preview", true)).use { scenario ->
                SystemClock.sleep(1500)
                capture(scenario, "02-preview")
            }
            RootKiosk.testConnect = {
                val pipes = instrumentation.uiAutomation.executeShellCommandRw("su 0 env CLASSPATH=${context.applicationInfo.sourceDir} /system/bin/app_process /system/bin app.d2lock.root.KioskBridge 0")
                val input = ParcelFileDescriptor.AutoCloseInputStream(pipes[0])
                val output = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                RootKiosk.Channel(input.bufferedReader(), output.bufferedWriter()) { runCatching { output.close() } }
            }
            Prefs.setKiosk(context, true)
            val scenario = ActivityScenario.launch<LockScreenActivity>(Intent(context, LockScreenActivity::class.java))
            try {
                var task = -1
                scenario.onActivity { task = it.taskId }
                TestDevice.focusTask(task)
                waitUntil { context.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED }
                waitUntil { runCatching {
                    onView(withText("Demo track")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                    true
                }.getOrDefault(false) }
                capture(scenario, "03-kiosk-lock")
                onView(withText("PIN")).perform(click())
                capture(scenario, "04-pin-prompt")
            } finally {
                val released = CountDownLatch(1)
                scenario.onActivity { RootKiosk.unlock(it) { released.countDown() } }
                assertTrue(released.await(15, TimeUnit.SECONDS))
                scenario.close()
            }
        } finally {
            mediaSession.release()
            RootKiosk.testConnect = null
            Prefs.setKiosk(context, false)
            pinFile.delete()
            File(pinFile.path + ".bak").delete()
        }
    }
}
