/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import app.d2lock.root.ShellRunner
import app.d2lock.xposed.PeriodicCredentialHook
import app.d2lock.xposed.StrongAuthDiagnostics
import org.json.JSONObject

/** Read-only status UI; the former timeout opt-out setting is ignored. */
object PeriodicCredentialSettings {
    fun show(activity: Activity) {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        body.addView(TextView(activity).apply {
            textSize = 14f
            text = "72-hour PIN / pattern diagnostics (read-only)\n\nD2 observes framework StrongAuth scheduler and request callbacks. Samsung credential requirements are unchanged. Registration alone does not prove callback execution.\n\nRequires D2's System Framework scope in LSPosed. A soft reboot may be needed after changing hook scope."

        })
        val status = TextView(activity).apply { textSize = 13f; text = "Reading diagnostic status…" }
        body.addView(status)
        val dialog = AlertDialog.Builder(activity).setTitle("Strong-auth diagnostics")
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Done", null).setNeutralButton("Refresh", null).create()
        fun refresh() {
            status.text = "Reading diagnostic status…"
            Thread {
                val text = runCatching {
                    val result = ShellRunner.run(listOf("su", "-c",
                        "pidof system_server; settings get global ${PeriodicCredentialHook.STATUS}"), 3)
                    check(result.succeeded())
                    val lines = result.output.trim().lines()
                    val pid = lines.first().trim().toInt()
                    val marker = JSONObject(lines.drop(1).joinToString("\n"))
                    val now = SystemClock.elapsedRealtime()
                    val boot = Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    check(StrongAuthDiagnostics.current(marker.optInt("version"), marker.optInt("pid"), pid,
                        marker.optInt("boot", -1), boot, marker.optLong("elapsed", -1), now))
                    "This boot\nD2 StrongAuth observer\nDiagnostic scheduler: ${marker.optString("scheduler")} • callbacks: ${marker.optInt("scheduleCallbacks")}\nScheduler details: ${marker.optString("schedulerDetail", "Not supplied by this build")}\nReason observer: ${marker.optString("reasonObserver")} • callbacks: ${marker.optInt("reasonCallbacks")}\nCredential: ${StrongAuthDiagnostics.credential(marker.optInt("credential", -1))}\nLast sampled flags: ${StrongAuthDiagnostics.reasons(marker.optInt("flags", -1))}\nLast request (not current state): ${StrongAuthDiagnostics.reasons(marker.optInt("lastRequest", -1))}\nSample age: ${(now - marker.optLong("elapsed")) / 1000}s\nPIN and pattern behavior remain unverified."
                }.getOrDefault("Diagnostics unavailable or stale for this boot. Check root access and D2's System Framework scope. Samsung authentication remains enabled.")
                activity.runOnUiThread { if (dialog.isShowing) status.text = text }
            }.start()
        }
        dialog.show()
        FeatureGlass.style(activity, dialog)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
        refresh()
    }
}
