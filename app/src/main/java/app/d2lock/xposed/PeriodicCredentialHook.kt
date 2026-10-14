/*
 * Copyright (c) 2026 D2 Project and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 * OneUIX reference: SoClear and contributors, Android.kt, revision
 * 591d09c9cc69faf3c8faf258e39869b2df7981f4 (AGPL-3.0).
 * D2 adaptation, October 14, 2026: opt-in configuration, exact signature,
 * primary-user/device-policy guards, fail-open behavior and runtime telemetry.
 */
package app.d2lock.xposed

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

/** Affects only periodic scheduling; never clears any strong-auth reason. */
internal object PeriodicCredentialHook {
    const val ENABLED = "d2_periodic_credential_timeout_optout"
    const val STATUS = "d2_periodic_credential_hook_status"
    private const val TAG = "D2PeriodicAuth"
    @Volatile private var context: Context? = null
    @Volatile private var state = "not_installed"
    private val skipped = AtomicInteger()

    fun install(module: XposedModule, loader: ClassLoader) {
        try {
            val owner = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")
            val method = owner.getDeclaredMethod("rescheduleStrongAuthTimeoutAlarm",
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            check(method.returnType == Void.TYPE)
            method.isAccessible = true
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val app = context
                val user = chain.args[1] as? Int
                val allow = runCatching {
                    app != null && user == 0 &&
                        Settings.Global.getInt(app.contentResolver, ENABLED, 0) == 1 &&
                        noAdminPolicy(app)
                }.getOrDefault(false)
                if (allow) {
                    skipped.incrementAndGet()
                    record("scheduler_suppressed")
                    null
                } else chain.proceed()
            }
            state = "installed"
            Log.i(TAG, "D2_PERIODIC_AUTH_INSTALLED target=${method.name} credential=PIN_PATTERN_PASSWORD verification=experimental")
        } catch (t: Throwable) {
            state = "unavailable"
            Log.w(TAG, "D2_PERIODIC_AUTH_UNAVAILABLE stock_retained=true", t)
        }
    }

    fun ready(app: Context) {
        context = app
        record(state)
    }

    private fun noAdminPolicy(app: Context): Boolean {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return false
        // These checks run with system_server identity. Missing APIs retain stock behavior.
        val deviceOwner = dpm.javaClass.getMethod("getDeviceOwnerComponentOnAnyUser").invoke(dpm)
        val profileOwner = dpm.javaClass.getMethod("getProfileOwnerAsUser", Int::class.javaPrimitiveType).invoke(dpm, 0)
        val timeout = dpm.javaClass.getMethod("getRequiredStrongAuthTimeout",
            android.content.ComponentName::class.java, Int::class.javaPrimitiveType).invoke(dpm, null, 0) as Long
        return PeriodicCredentialPolicy.allowed(true, 0, deviceOwner != null, profileOwner != null, timeout)
    }

    private fun record(event: String) {
        val value = "${Process.myPid()}|$event|${skipped.get()}"
        runCatching { context?.let { Settings.Global.putString(it.contentResolver, STATUS, value) } }
        Log.i(TAG, "D2_PERIODIC_AUTH_STATUS $value")
    }
}
