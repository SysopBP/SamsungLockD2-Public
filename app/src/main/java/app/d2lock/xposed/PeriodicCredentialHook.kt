/*
 * Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only
 * Reference: SoClear/OneUIX Android.kt @ 591d09c9cc69faf3c8faf258e39869b2df7981f4.
 * D2 modification 2026-10-14: replace scheduler suppression with read-only diagnostics.
 */
package app.d2lock.xposed

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Never changes scheduling, credentials, arguments, results or strong-auth flags. */
internal object PeriodicCredentialHook {
    const val ENABLED = "d2_periodic_credential_timeout_optout" // Legacy setting is ignored.
    const val STATUS = "d2_periodic_credential_hook_status"
    private const val TAG = "D2PeriodicAuth"
    @Volatile private var context: Context? = null
    @Volatile private var scheduler = "unavailable"
    @Volatile private var reasons = "unavailable"
    @Volatile private var lastRequest = -1
    @Volatile private var schedulerDetail = "not_checked"
    private val schedules = AtomicInteger()
    private val requests = AtomicInteger()
    private val queued = AtomicBoolean()
    private var worker: Handler? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        io.github.soclear.oneuix.hook.Android.periodicCredentialObserver = { enqueue() }
        // Created at registration, never on a framework callback stack.
        worker = Handler(HandlerThread("D2StrongAuthDiagnostics").apply { start() }.looper)
        observe(module, loader, "rescheduleStrongAuthTimeoutAlarm",
            arrayOf(Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), true)
        observe(module, loader, "requireStrongAuth",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), false)
    }

    private fun observe(module: XposedModule, loader: ClassLoader, name: String,
                        signature: Array<Class<*>>, scheduling: Boolean) {
        try {
            val owner = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")
            val candidates = owner.declaredMethods.filter { it.name == name }
            Log.i(TAG, "D2_STRONG_AUTH_CAPABILITY method=$name signatures=" +
                candidates.joinToString { it.parameterTypes.joinToString(",", "(", ")") { type -> type.simpleName } })
            val method = if (scheduling) {
                // OEM firmware can add a timeout argument. Never guess a user index.
                val supported = candidates.filter {
                    SchedulerSignature.supported(it.parameterTypes.map { type -> type.name }.toTypedArray()) &&
                        it.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
                schedulerDetail = "available=" + candidates.joinToString { it.toGenericString() }
                check(supported.size == 1) { "Expected one supported scheduler, found ${supported.size}; $schedulerDetail" }
                supported.single()
            } else owner.getDeclaredMethod(name, *signature)
            check(method.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(method.modifiers))
            method.isAccessible = true
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed() // Exactly once; Android exceptions and results are preserved.
                runCatching {
                    val user = chain.args.getOrNull(if (scheduling) SchedulerSignature.userIndex(method.parameterTypes.map { it.name }.toTypedArray()) else 1) as? Int
                    // Requests for all users are relevant, but are NOT interpreted as current flags.
                    if (user == 0 || (!scheduling && user == -1)) {
                        if (scheduling) schedules.incrementAndGet() else {
                            requests.incrementAndGet()
                            lastRequest = chain.args[0] as? Int ?: -1
                        }
                        enqueue()
                    }
                }
                result
            }
            if (scheduling) {
                scheduler = "installed"
                schedulerDetail = method.toGenericString()
            } else reasons = "installed"
            Log.i(TAG, "D2_STRONG_AUTH_REGISTERED method=$name stock_retained=true")
        } catch (error: Throwable) {
            if (scheduling) scheduler = if (error is ClassNotFoundException || error is IllegalStateException) "unavailable" else "failed"
            else reasons = if (error is ClassNotFoundException || error is NoSuchMethodException) "unavailable" else "failed"
            if (scheduling) schedulerDetail = "$schedulerDetail; error=${error.javaClass.simpleName}: ${error.message}"
            enqueue()
            Log.w(TAG, "D2_STRONG_AUTH_UNAVAILABLE method=$name stock_retained=true", error)
        }
    }

    fun ready(app: Context) {
        context = app
        enqueue()
    }

    private fun enqueue() {
        if (context == null || !queued.compareAndSet(false, true)) return
        if (worker?.postDelayed({
            queued.set(false)
            runCatching { record() }.onFailure { Log.w(TAG, "D2_STRONG_AUTH_SNAPSHOT_FAILED", it) }
        }, 1_500L) != true) queued.set(false)
    }

    private fun record() {
        val app = context ?: return
        var flags = -1
        var credential = -1
        // Query only metadata off the callback stack; never request credential material.
        val identity = Binder.clearCallingIdentity()
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java).invoke(null, "lock_settings")
            val service = Class.forName("com.android.internal.widget.ILockSettings\$Stub")
                .getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val api = Class.forName("com.android.internal.widget.ILockSettings")
            flags = runCatching { api.getMethod("getStrongAuthForUser", Int::class.javaPrimitiveType)
                .invoke(service, 0) as Int }.getOrDefault(-1)
            credential = runCatching { api.getMethod("getCredentialType", Int::class.javaPrimitiveType)
                .invoke(service, 0) as Int }.getOrDefault(-1)
        } catch (error: Throwable) {
            Log.w(TAG, "D2_STRONG_AUTH_QUERY_UNAVAILABLE stock_retained=true", error)
        } finally { Binder.restoreCallingIdentity(identity) }
        val oneuiStatus = io.github.soclear.oneuix.hook.Android.periodicCredentialStatus()
        val oneuiInstalled = oneuiStatus.optString("state") == "installed"
        val d2Installed = scheduler == "installed"
        val oneuiCount = oneuiStatus.optInt("observedSchedules", 0)
        val d2Count = schedules.get()
        val observerComparison = when {
            !oneuiInstalled || !d2Installed -> "unavailable"
            oneuiCount == 0 && d2Count == 0 -> "waiting"
            oneuiCount > 0 && d2Count > 0 -> "both_observed"
            oneuiCount > 0 -> "oneui_only"
            else -> "d2_only"
        }
        val snapshot = JSONObject()
            .put("version", 3)
            .put("observerComparison", observerComparison).put("pid", Process.myPid())
            .put("boot", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1))
            .put("elapsed", SystemClock.elapsedRealtime())
            .put("oneuiPeriodic", oneuiStatus)
            .put("scheduler", scheduler).put("schedulerDetail", schedulerDetail).put("reasonObserver", reasons)
            .put("scheduleCallbackScope", "user_0_completed_calls").put("featureVerified", false)
            .put("scheduleCallbacks", schedules.get()).put("reasonCallbacks", requests.get())
            .put("lastRequest", lastRequest).put("flags", flags).put("credential", credential)
        Settings.Global.putString(app.contentResolver, STATUS, snapshot.toString())
        // Explicit system-origin signal; widget reads the trusted stored snapshot.
        app.sendBroadcast(android.content.Intent("app.d2lock.widget.PERIODIC_STATUS")
            .setComponent(android.content.ComponentName("app.d2lock", "app.d2lock.widget.D2Widget")))
        Log.i(TAG, "D2_STRONG_AUTH_SNAPSHOT $snapshot stock_retained=true")
    }
}
