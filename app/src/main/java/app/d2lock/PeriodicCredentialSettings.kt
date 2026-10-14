/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.d2lock.root.ShellRunner
import app.d2lock.xposed.PeriodicCredentialHook

/** Privileged Global settings transport; no authentication credentials are stored here. */
object PeriodicCredentialSettings {
    fun show(activity: Activity) {
        val enabled = Settings.Global.getInt(activity.contentResolver, PeriodicCredentialHook.ENABLED, 0) == 1
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val detail = TextView(activity).apply {
            text = "Experimental: periodic Samsung PIN / pattern check\n\nTargets Samsung’s periodic backup-credential timeout (typically 72 hours), not D2’s PIN. PIN and pattern share a candidate scheduler path but both need device testing.\n\nThis reduces a periodic authentication safeguard. It does not remove reboot/first-unlock, lockdown, failed-attempt or administrator requirements. Samsung face recognition is separate.\n\nRequires root plus D2 enabled for System Framework in LSPosed. After changing it, soft reboot through your root manager and unlock Samsung once using its backup credential. An existing timeout is not cleared.\n\nTo restore: turn OFF and soft reboot. Device-policy-managed users retain stock scheduling."
            textSize = 14f
        }
        body.addView(detail)
        val status = TextView(activity).apply { text = "Checking hook status…"; textSize = 13f }
        body.addView(status)
        val toggle = Switch(activity).apply {
            text = "Periodic PIN / pattern timeout opt-out"
            isChecked = enabled
        }
        body.addView(toggle)
        val dialog = AlertDialog.Builder(activity).setTitle("Periodic credential check")
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Done", null).create()
        fun refresh() {
            Thread {
                val result = runCatching { ShellRunner.run(listOf("su", "-c",
                    "pidof system_server; settings get global ${PeriodicCredentialHook.STATUS}"), 3) }.getOrNull()
                val lines = result?.output?.trim()?.lines().orEmpty()
                val pid = lines.firstOrNull()?.trim()
                val marker = lines.getOrNull(1)?.split('|').orEmpty()
                val text = if (result?.succeeded() == true && marker.size == 3 && marker[0] == pid)
                    "This boot: ${marker[1]} • suppressed schedules: ${marker[2]}\nInstalled does not mean PIN/pattern behavior is verified."
                else "Hook status unverified for this boot. Check System Framework scope and soft reboot."
                activity.runOnUiThread { if (dialog.isShowing) status.text = text }
            }.start()
        }
        lateinit var bindToggle: () -> Unit
        fun change(value: Boolean) {
            toggle.isEnabled = false
            Thread {
                val result = runCatching { ShellRunner.run(listOf("su", "-c",
                    "settings put global ${PeriodicCredentialHook.ENABLED} ${if (value) 1 else 0}; settings get global ${PeriodicCredentialHook.ENABLED}"), 4) }.getOrNull()
                val saved = result?.succeeded() == true && result.output.trim() == (if (value) "1" else "0")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    toggle.isEnabled = true
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = if (saved) value else !value
                    bindToggle()
                    Toast.makeText(activity, if (saved) "Saved. Soft reboot, then test PIN and pattern separately." else "Could not save. Root permission is required.", Toast.LENGTH_LONG).show()
                    refresh()
                }
            }.start()
        }
        // Listener is reattached after programmatic recovery to avoid recursive writes.
        val listener: (Boolean) -> Unit = { value ->
            if (value) AlertDialog.Builder(activity).setTitle("Enable experimental timeout opt-out?")
                .setMessage("This reduces Samsung’s periodic credential safeguard. It is not verified for PIN or pattern on your firmware yet. Keep your backup credential available.")
                .setPositiveButton("Enable") { _, _ -> change(true) }
                .setNegativeButton("Cancel") { _, _ -> toggle.setOnCheckedChangeListener(null); toggle.isChecked = false; bindToggle() }
                .setOnCancelListener { toggle.setOnCheckedChangeListener(null); toggle.isChecked = false; bindToggle() }.create().also { confirm -> confirm.setOnShowListener { FeatureGlass.style(activity,confirm) }; confirm.show() }
            else change(false)
        }
        bindToggle = { toggle.setOnCheckedChangeListener { _, checked -> listener(checked) } }
        bindToggle()
        dialog.show()
        FeatureGlass.style(activity,dialog)
        refresh()
        dialog.setOnDismissListener { toggle.setOnCheckedChangeListener(null) }
    }
}
