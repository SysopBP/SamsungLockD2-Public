/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock;

import android.content.Context
import android.content.Intent
import android.os.Build
import app.d2lock.root.ShellRunner
import app.d2lock.xposed.PeriodicCredentialHook
import app.d2lock.xposed.PeriodicCredentialPolicy

/**
 * App-side writer for the System Server strong-auth interval.
 *
 * The Xposed hook runs in System Server and reads Settings.Global, so the choice is mirrored
 * there with root and refreshed with a broadcast. Stock writes the value too; the hook simply
 * leaves Android's own timeout alone when it sees stock.
 */
object PeriodicCredentialControl {
    val choices = listOf("Stock \u00b7 72 hours", "7 days", "30 days", "Never")
    val values = listOf(
        PeriodicCredentialPolicy.STOCK_HOURS,
        PeriodicCredentialPolicy.WEEK_HOURS,
        PeriodicCredentialPolicy.MONTH_HOURS,
        PeriodicCredentialPolicy.NEVER_HOURS
    )

    fun hours(context: Context) = PeriodicCredentialPolicy.normalise(Prefs.strongAuthTimeoutHours(context))

    fun label(context: Context) = PeriodicCredentialPolicy.label(hours(context))

    fun selected(context: Context) = values.indexOf(hours(context)).coerceAtLeast(0)

    /** Mirrors the choice into Settings.Global and asks System Server to re-read it. */
    fun apply(context: Context, hours: Int): Pair<Boolean, String> {
        val value = PeriodicCredentialPolicy.normalise(hours)
        Prefs.setStrongAuthTimeoutHours(context, value)
        val result = runCatching {
            ShellRunner.run(listOf("su", "-c", "settings put global ${PeriodicCredentialHook.POLICY} $value"), 5)
        }.getOrNull()
        if (result == null || !result.succeeded()) {
            return false to "Root shell unavailable; System Server keeps the previous interval."
        }
        refresh(context)
        return true to "PIN re-verification: ${PeriodicCredentialPolicy.label(value)}"
    }

    /** Boot and app-open sync so the mirror can never drift from the stored choice. */
    fun sync(context: Context) {
        Thread {
            runCatching { apply(context, Prefs.strongAuthTimeoutHours(context)) }
        }.start()
    }

    private fun refresh(context: Context) {
        runCatching {
            val intent = Intent(PeriodicCredentialHook.REFRESH).setPackage("android")
            if (Build.VERSION.SDK_INT >= 34) {
                context.sendBroadcast(intent, null,
                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else context.sendBroadcast(intent)
        }
    }
}
