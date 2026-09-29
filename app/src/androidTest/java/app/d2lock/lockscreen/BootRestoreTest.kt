package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.Prefs
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootRestoreTest {
    private class RestoreContext(base: Context) : ContextWrapper(base) {
        val prefix = "boot-test-${System.nanoTime()}-"
        val folder = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        var receiver: BroadcastReceiver? = null
        var failStart = false
        val starts = mutableListOf<Intent>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = folder
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(prefix + name.also { names.add(it) }, mode)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            assertTrue(filter.hasAction(Intent.ACTION_USER_UNLOCKED))
            this.receiver = receiver
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { this.receiver = null }
        override fun startForegroundService(service: Intent): ComponentName? {
            if (failStart) throw IllegalStateException("Simulated rejected service start")
            starts.add(Intent(service))
            return service.component
        }
        fun pending() = getSharedPreferences("guardian_boot_state", MODE_PRIVATE)
            .getBoolean("pending_restore", false)
        fun clean() {
            Prefs.setEnabled(this, false)
            CompanionStartupReceiver.restoreFromFramework(this, true)
            names.toList().forEach { baseContext.deleteSharedPreferences(prefix + it) }
            folder.deleteRecursively()
        }
    }

    private fun isolated(test: (RestoreContext) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = RestoreContext(instrumentation.targetContext)
            try {
                // Only configured() is used; no credential is read or verified.
                File(context.folder, "d2-pin.json").writeText("{}")
                Prefs.setEnabled(context, true)
                test(context)
            } finally { context.clean() }
        }
    }

    @Test fun unlockDuringRegistrationStartsRestoreAndRemovesReceiver() = isolated { context ->
        // Emulator is already unlocked: simulate an earlier locked snapshot.
        CompanionStartupReceiver.restoreFromFramework(context, false)
        assertEquals(1, context.starts.size)
        assertTrue(context.starts.single().getBooleanExtra("d2_post_boot", false))
        assertFalse(context.pending())
        assertNull(context.receiver)
    }

    @Test fun failedEarlyStartKeepsPendingForBootFallback() = isolated { context ->
        context.failStart = true
        CompanionStartupReceiver.restoreFromFramework(context, false)
        assertTrue(context.pending())
        assertNotNull(context.receiver)
        context.failStart = false
        CompanionStartupReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, context.starts.size)
        assertFalse(context.pending())
        assertNull(context.receiver)
    }

    @Test fun disabledGuardianDoesNotLaunchAfterUnlock() = isolated { context ->
        Prefs.setEnabled(context, false)
        CompanionStartupReceiver.restoreFromFramework(context, false)
        assertTrue(context.starts.isEmpty())
        assertFalse(context.pending())
        assertNull(context.receiver)
    }
}
