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

/** PIN re-verification control plus read-only StrongAuth diagnostics. */
object PeriodicCredentialSettings {
    fun show(activity: Activity) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val gap = (12 * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(activity).apply {
            textSize = 14f
            text = "PIN re-verification\n\nAndroid normally asks for your PIN (not your fingerprint) every 72 hours. D2 rewrites that interval inside System Server. Your Samsung credential is never changed.\n\nNeeds D2's System Framework scope in LSPosed, and root for the mirror. Applies from the next credential use."
        })
        val choice = TextView(activity).apply {
            textSize = 15f
            text = "Require PIN again  \u2022  " + PeriodicCredentialControl.label(activity) + "  \u203a"
            setPadding(gap, gap, gap, gap)
            setOnClickListener {
                GuardianDialogs.showChoices(activity, "Require PIN again",
                    PeriodicCredentialControl.choices.toTypedArray(),
                    PeriodicCredentialControl.selected(activity)) { dialog, which ->
                    dialog.dismiss()
                    Thread {
                        val result = PeriodicCredentialControl.apply(activity, PeriodicCredentialControl.values[which])
                        activity.runOnUiThread {
                            choice.text = "Require PIN again  \u2022  " + PeriodicCredentialControl.label(activity) + "  \u203a"
                            android.widget.Toast.makeText(activity, result.second, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }
        }
        body.addView(choice)
        val status = TextView(activity).apply { textSize = 13f; text = "Reading diagnostic status\u2026" }
        body.addView(status)
        val dialog = AlertDialog.Builder(activity).setTitle("PIN re-verification")
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Done", null).setNeutralButton("Refresh", null).create()
        fun refresh() {
            status.text = "Reading diagnostic status\u2026"
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
                    "This boot\nD2 StrongAuth hook\nInterval: ${marker.optString("policyLabel", "not read")}\nScheduler: ${marker.optString("scheduler")}  \u2022  timeouts rewritten: ${marker.optInt("timeoutRewrites")}\nScheduler details: ${marker.optString("schedulerDetail", "Not supplied by this build")}\nReason observer: ${marker.optString("reasonObserver")}  \u2022  callbacks: ${marker.optInt("reasonCallbacks")}\nCredential: ${StrongAuthDiagnostics.credential(marker.optInt("credential", -1))}\nLast sampled flags: ${StrongAuthDiagnostics.reasons(marker.optInt("flags", -1))}\nSample age: ${(now - marker.optLong("elapsed")) / 1000}s\n\n\"Timeouts rewritten\" only counts callbacks after the hook installed. Pick an interval, unlock once, then refresh."
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
