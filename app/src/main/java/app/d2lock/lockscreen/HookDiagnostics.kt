package app.d2lock.lockscreen

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Device-protected evidence, scoped to the current OS boot. Never authorizes authentication. */
object HookDiagnostics {
    private val numbers = listOf("hook_boot_installed", "hook_wake_installed", "hook_failures", "hook_unavailable", "hook_boot_executed", "hook_wake_executed", "hook_wake_deduped", "hook_elapsed", "credential_schedules", "credential_requests")
    private val strings = listOf("credential_scheduler", "credential_request")
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("d2_hook_evidence", Context.MODE_PRIVATE)
    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun record(c: Context, i: Intent) {
        val p = prefs(c)
        val edit = p.edit()
        if (p.getInt("boot", -2) != boot(c)) edit.clear()
        edit.putInt("boot", boot(c)).putLong("checked", System.currentTimeMillis())
        numbers.filter { i.hasExtra(it) }.forEach { edit.putLong(it, HookEvidenceNumbers.value(i.extras?.get(it))) }
        strings.filter { i.hasExtra(it) }.forEach { edit.putString(it, i.getStringExtra(it)) }
        if (i.getStringExtra("event") in listOf("BOOT_READY", "WAKE")) {
            edit.putString("last_event", i.getStringExtra("event")).putLong("last_event_time", System.currentTimeMillis())
        }
        edit.apply()
    }
    fun summary(c: Context): String {
        val p = prefs(c)
        if (p.getInt("boot", -2) != boot(c)) return "Not yet observed this boot"
        fun count(k: String) = if (p.contains(k)) p.getLong(k, -1).toString() else "?"
        fun state(k: String) = if (!p.contains(k)) "not yet observed" else if (p.getLong(k, 0) > 0) "installed (${count(k)})" else "no installed target"
        val d = KeyguardSignalReceiver.decisionMetrics(c)
        return "System Server: ${KeyguardSignalReceiver.systemHealth(c).status}\nBoot: ${state("hook_boot_installed")} · executed ${count("hook_boot_executed")}\nWake: ${state("hook_wake_installed")} · executed ${count("hook_wake_executed")}\nRegistration failures: ${count("hook_failures")} · unavailable targets: ${count("hook_unavailable")}\nWake deduplicated: ${count("hook_wake_deduped")} · Guardian suppressed (cumulative): ${d.suppressed}\nLast successful event: ${p.getString("last_event", "not yet observed")} · ${time(p.getLong("last_event_time", 0))}\nDiagnostic snapshot: ${time(p.getLong("checked", 0))}\nFramework elapsed: ${count("hook_elapsed")} ms\n72-hour diagnostics: ${credential(c)}\nCounters are observations, not credential verification. Hook installation alone does not prove execution."
    }
    fun credentialCompact(c: Context): String {
        val p = prefs(c)
        if (p.getInt("boot", -2) != boot(c)) return "not observed"
        val status = p.getString("credential_scheduler", "not observed")
        val calls = p.getLong("credential_schedules", 0)
        return if (calls > 0) "observed $calls" else when (status) {
            "installed" -> "registered / 0"
            "partially installed / failed" -> "partial failure / 0"
            else -> "$status / 0"
        }
    }
    fun health(c: Context): String {
        val p = prefs(c)
        if (p.getInt("boot", -2) != boot(c) || !p.contains("hook_boot_installed")) return "not observed"
        return when {
            p.getLong("hook_failures", 0) > 0 -> "registration failed"
            p.getLong("hook_unavailable", 0) > 0 -> "target unavailable"
            p.getLong("hook_boot_executed", 0) > 0 || p.getLong("hook_wake_executed", 0) > 0 -> "executed"
            else -> "installed · no event"
        }
    }
    fun credential(c: Context): String {
        val p = prefs(c)
        if (p.getInt("boot", -2) != boot(c)) return "not yet observed"
        return "Scheduler ${p.getString("credential_scheduler", "not yet observed")} (${p.getLong("credential_schedules", 0)} calls); strong-auth ${p.getString("credential_request", "not yet observed")} (${p.getLong("credential_requests", 0)} calls). Required PIN/pattern retained."
    }
    private fun time(t: Long) = if (t <= 0) "not yet observed" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))
}
