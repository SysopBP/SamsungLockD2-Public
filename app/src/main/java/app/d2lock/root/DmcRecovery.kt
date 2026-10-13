package app.d2lock.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Mandatory for rooted primary-user installs, independent of Guardian's optional root-mode switch. */
object DmcRecovery {
    data class Status(
        val root: Boolean? = null,
        val screenLock: Int? = null,
        val maintenance: Int? = null,
        val atCommand: Int? = null,
        val downloadAllowed: Boolean? = null,
        val verified: Boolean = false,
        val detail: String = "Waiting for automatic root check",
        val checkedAt: Long = 0L
    ) {
        val targetsMet: Boolean get() = verified && root == true && screenLock == 0 &&
            maintenance == 0 && atCommand == 1 && downloadAllowed == true

        fun report(): String {
            fun flag(value: Int?) = when(value) { 0 -> "Disabled"; 1 -> "Enabled"; else -> "Unknown" }
            return buildString {
                appendLine("Automatic root recovery: ${if(targetsMet) "Verified" else "Not verified"}")
                appendLine("Root: ${when(root) { true -> "Available"; false -> "Unavailable"; null -> if(checkedAt == 0L) "Checking" else "Not verified" }}")
                appendLine("Screen lock (DMC): ${flag(screenLock)}  •  Target: Disabled")
                appendLine("Maintenance Mode (DMC): ${flag(maintenance)}  •  Target: Disabled")
                appendLine("Download Mode AT Command: ${flag(atCommand)}  •  Target: Enabled")
                appendLine("Download Mode: ${when(downloadAllowed) { true -> "Allowed"; false -> "Blocked"; null -> "Unknown" }}  •  Target: Allowed")
                appendLine()
                appendLine(detail)
                if (checkedAt > 0L) appendLine("Checked: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(checkedAt))}")
                append("Runs automatically with granted root. DMC flags are separate from Android credentials and D2's PIN/Pattern.")
            }
        }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var lastAttempt = -30_000L
    private const val PREFS = "d2_dmc_recovery"
    private const val TAG = "D2DmcRecovery"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun status(context: Context): Status {
        val p = prefs(context)
        if (p.getInt("boot", -2) != bootCount(context)) return Status()
        fun value(key: String) = p.getInt(key, -1).takeIf { it in 0..1 }
        return Status(value("root")?.let { it == 1 }, value("lock"), value("maint"), value("at"),
            value("download")?.let { it == 1 }, p.getBoolean("verified", false),
            p.getString("detail", null) ?: "Waiting for automatic root check", p.getLong("checked", 0L))
    }

    /** Callback always completes promptly even if another boot source already owns the check. */
    fun request(context: Context, source: String, retry: Int = 0, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) { done?.invoke(); return }
        if (retry == 0 && SystemClock.elapsedRealtime() - lastAttempt < 10_000L) {
            busy.set(false); done?.invoke(); return
        }
        worker.execute {
            var success = false
            try {
                lastAttempt = SystemClock.elapsedRealtime()
                val result = if (Process.myUid() / 100000 != 0) {
                    Status(detail = "Automatic DMC recovery supports the primary user only")
                } else {
                    val apk = app.applicationInfo.sourceDir
                    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                    val command = "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin app.d2lock.root.DmcVaultBridge apply"
                    val output = ShellRunner.run(listOf("su", "-c", command), 6)
                    val line = output.output.lineSequence().lastOrNull { it.startsWith("D2_DMC_V1 ") }
                    val fields = line?.split(' ', limit = 8)
                    if (fields != null && fields.size == 8) {
                        fun field(i: Int) = fields[i].toIntOrNull()?.takeIf { it in 0..1 }
                        Status(field(1)?.let { it == 1 }, field(3), field(4), field(5), field(6)?.let { it == 1 },
                            output.succeeded() && !output.truncated && fields[2] == "1", fields[7])
                    } else Status(detail = when {
                        output.timedOut -> "Root or DMC service did not respond; automatic retry pending"
                        else -> "Root/backend unavailable: ${output.output.trim().take(240).ifBlank { "no response" }}"
                    })
                }
                val stamped = result.copy(checkedAt = System.currentTimeMillis())
                success = stamped.targetsMet
                prefs(app).edit().putInt("boot", bootCount(app))
                    .putInt("root", stamped.root?.let { if(it) 1 else 0 } ?: -1)
                    .putInt("lock", stamped.screenLock ?: -1).putInt("maint", stamped.maintenance ?: -1)
                    .putInt("at", stamped.atCommand ?: -1)
                    .putInt("download", stamped.downloadAllowed?.let { if(it) 1 else 0 } ?: -1)
                    .putBoolean("verified", stamped.verified).putString("detail", stamped.detail)
                    .putLong("checked", stamped.checkedAt).commit()
                Log.i(TAG, "DMC_AUTO_CHECK source=$source retry=$retry targetsMet=$success detail=${stamped.detail}")
            } catch (error: Exception) {
                prefs(app).edit().putInt("boot", bootCount(app)).putBoolean("verified", false)
                    .putInt("root", -1).putInt("lock", -1).putInt("maint", -1).putInt("at", -1).putInt("download", -1)
                    .putString("detail", "Root/backend unavailable: ${error.message}")
                    .putLong("checked", System.currentTimeMillis()).commit()
                Log.w(TAG, "DMC_AUTO_CHECK_FAILED source=$source", error)
            } finally {
                busy.set(false)
                done?.invoke()
            }
            if (!success && retry < 3) worker.schedule({ request(app, source, retry + 1) },
                longArrayOf(5, 15, 30)[retry], TimeUnit.SECONDS)
        }
    }

    fun refresh(context: Context, done: (Status) -> Unit) {
        request(context, "status_dialog") { main.post { done(status(context)) } }
    }
}
