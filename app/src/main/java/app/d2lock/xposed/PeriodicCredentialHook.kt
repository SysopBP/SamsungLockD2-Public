/*
 * Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only
 * Reference: SoClear/OneUIX Android.kt @ 591d09c9cc69faf3c8faf258e39869b2df7981f4.
 * D2 modification 2026-10-16: rewrite the scheduler timeout argument, keep read-only diagnostics.
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

/**
 * Makes Android's periodic strong-auth re-verification (stock: every 72 hours) selectable.
 *
 * Android stays authoritative: only the timeout argument is rewritten before chain.proceed(),
 * so the framework's own field, alarm and bookkeeping remain consistent. A "now" argument is
 * told apart from the timeout by distance from elapsedRealtime(), because SchedulerSignature
 * proves two or three long arguments in no fixed order. Stock leaves the argument untouched.
 */
internal object PeriodicCredentialHook {
    const val POLICY = "d2_strong_auth_timeout_hours"
    const val REFRESH = "app.d2lock.action.STRONG_AUTH_REFRESH"
    const val ENABLED = "d2_periodic_credential_timeout_optout" // Legacy setting is ignored.
    const val STATUS = "d2_periodic_credential_hook_status"
    private const val TAG = "D2PeriodicAuth"
    /** A "now" argument sits within this window of elapsedRealtime(); a timeout never does. */
    private const val NOW_TOLERANCE_MS = 300_000L
    @Volatile private var context: Context? = null
    @Volatile private var scheduler = "unavailable"
    @Volatile private var reasons = "unavailable"
    @Volatile private var lastRequest = -1
    @Volatile private var schedulerDetail = "not_checked"
    @Volatile private var policyHours = 0
    @Volatile private var policyRead = "not_read"
    private val schedules = AtomicInteger()
    private val requests = AtomicInteger()
    private val rewrites = AtomicInteger()
    private val queued = AtomicBoolean()
    private var worker: Handler? = null
    /** 0x10 = STRONG_AUTH_REASON_TIMEOUT, the periodic 72-hour rule. */
    private const val REASON_TIMEOUT = 0x10
    private val reasonDowngrades = AtomicInteger()
    private val refreshRequests = AtomicInteger()
    private val refreshApplied = AtomicInteger()
    private val allScheduleCallbacks = AtomicInteger()
    @Volatile private var lastRefreshElapsed = -1L
    @Volatile private var timeoutMechanism = "none"
    private var timeoutField: java.lang.reflect.Field? = null
    private var timeoutSetter: java.lang.reflect.Method? = null
    private val reflectionFailures = AtomicInteger()
    @Volatile private var reflectionDiagnostic = "pending"
    @Volatile private var lastSchedulerElapsed = -1L
    @Volatile private var schedulerTargetClass = "not_observed"

    fun install(module: XposedModule, loader: ClassLoader) {
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
                // Stock changes nothing; otherwise push the interval or drop the timeout reason.
                val rewritten = if (scheduling) prepareTimeout(chain.thisObject, chain.args.toList()) else null
                val downgraded = if (!scheduling && periodicNever()) stripTimeoutReason(chain.args.toList()) else null
                if (scheduling) {
                    allScheduleCallbacks.incrementAndGet()
                    lastSchedulerElapsed = SystemClock.elapsedRealtime()
                    schedulerTargetClass = chain.thisObject?.javaClass?.name ?: "null"
                }
                val result = when {
                    rewritten != null -> chain.proceed(rewritten)
                    downgraded != null -> chain.proceed(downgraded)
                    else -> chain.proceed()
                }
                runCatching {
                    val user = chain.args.getOrNull(if (scheduling) SchedulerSignature.userIndex(method.parameterTypes.map { type -> type.name }.toTypedArray()) else 1) as? Int
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

    /**
     * Push the configured interval into the framework before it runs.
     *
     * The live signature is rescheduleStrongAuthTimeoutAlarm(int, long) - one int and one long,
     * and that long is "now" here, so the timeout comes from the framework's own field. Write
     * the field first, then only rewrite a long that is clearly not "now".
     */
    private fun prepareTimeout(target: Any?, args: List<Any?>?): Array<Any?>? {
        val timeout = PeriodicCredentialPolicy.timeoutMs(policyHours)
        if (timeout <= 0L) return null
        if (target != null) applyTimeout(target, timeout)
        if (args == null) return null
        val now = SystemClock.elapsedRealtime()
        val copy = args.toMutableList()
        var changed = false
        for (index in copy.indices) {
            val value = copy[index] as? Long ?: continue
            if (kotlin.math.abs(value - now) > NOW_TOLERANCE_MS) {
                copy[index] = timeout
                changed = true
            }
        }
        if (changed) rewrites.incrementAndGet()
        return if (changed) copy.toTypedArray() else null
    }

    /** Writes mStrongAuthTimeoutMs, or calls setTimeoutMs, whichever this build exposes. */
    private fun applyTimeout(target: Any, ms: Long) {
        runCatching {
            if (timeoutSetter == null) {
                timeoutSetter = target.javaClass
                    .getDeclaredMethod("setTimeoutMs", Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            }
            timeoutSetter!!.invoke(target, ms)
            timeoutMechanism = "setTimeoutMs"
        }.onFailure {
            runCatching {
                if (timeoutField == null) {
                    timeoutField = target.javaClass.getDeclaredField("mStrongAuthTimeoutMs")
                        .apply { isAccessible = true }
                }
                timeoutField!!.setLong(target, ms)
                timeoutMechanism = "mStrongAuthTimeoutMs"
            }.onFailure { fieldError ->
                timeoutMechanism = "unavailable:" + fieldError.javaClass.simpleName
                reflectionFailures.incrementAndGet()
                // Read-only metadata for diagnosing OEM implementation differences.
                runCatching {
                    val hierarchy = generateSequence(target.javaClass) { it.superclass }.take(5).toList()
                    val fields = hierarchy.flatMap { cls ->
                        cls.declaredFields.filter { field ->
                            field.name.contains("timeout", true) || field.name.contains("alarm", true) ||
                                field.name.contains("strongAuth", true)
                        }.map { field -> cls.simpleName + "." + field.name + ":" + field.type.simpleName }
                    }
                    val methods = hierarchy.flatMap { cls ->
                        cls.declaredMethods.filter { method ->
                            method.name.contains("timeout", true) || method.name.contains("alarm", true)
                        }.map { method -> cls.simpleName + "." + method.name + "(" +
                            method.parameterTypes.joinToString { type -> type.simpleName } + ")" }
                    }
                    reflectionDiagnostic = ("target=" + target.javaClass.name + " fields=" + fields.joinToString(";") +
                        " methods=" + methods.joinToString(";") + " fieldError=" + fieldError.javaClass.simpleName).take(3500)
                    Log.w(TAG, "D2_STRONG_AUTH_REFLECTION_DIAGNOSTIC " + reflectionDiagnostic)
                }.onFailure {
                    reflectionDiagnostic = "diagnostic_failed:" + it.javaClass.simpleName
                    Log.w(TAG, "D2_STRONG_AUTH_DIAGNOSTIC_FAILED", it)
                }
            }
        }
    }

    private fun periodicNever() =
        PeriodicCredentialPolicy.normalise(policyHours) == PeriodicCredentialPolicy.NEVER_HOURS

    /**
     * "Never" is enforced on the reason call, not on the alarm: even if the alarm fires we
     * refuse to record STRONG_AUTH_REASON_TIMEOUT, so the keyguard never demands the credential
     * for it. Independent of the alarm maths, and this hook already fires (callbacks: 2).
     */
    private fun stripTimeoutReason(args: List<Any?>?): Array<Any?>? {
        val reason = args?.getOrNull(0) as? Int ?: return null
        if (reason and REASON_TIMEOUT == 0) return null
        val copy = args.toMutableList()
        copy[0] = reason and REASON_TIMEOUT.inv()
        reasonDowngrades.incrementAndGet()
        return copy.toTypedArray()
    }

    fun ready(app: Context) {
        context = app
        // Boot handoff runs off the framework callback stack, so an IPC read is safe here.
        readPolicy()
        enqueue()
    }

    /** Called from the System Server receiver when the app changes the interval. */
    fun refresh(app: Context) {
        context = app
        refreshRequests.incrementAndGet()
        Log.i(TAG, "D2_STRONG_AUTH_REFRESH_ACCEPTED requests=${refreshRequests.get()}")
        enqueue()
    }

    private fun readPolicy() {
        val app = context ?: return
        policyHours = runCatching { Settings.Global.getInt(app.contentResolver, POLICY, 0) }
            .getOrDefault(policyHours)
        policyRead = PeriodicCredentialPolicy.label(policyHours)
    }

    private fun enqueue() {
        if (context == null || !queued.compareAndSet(false, true)) return
        if (worker?.postDelayed({
            queued.set(false)
            runCatching {
                readPolicy()
                if (refreshRequests.get() > refreshApplied.get()) {
                    refreshApplied.set(refreshRequests.get())
                    lastRefreshElapsed = SystemClock.elapsedRealtime()
                    Log.i(TAG, "D2_STRONG_AUTH_REFRESH_APPLIED hours=$policyHours count=${refreshApplied.get()}")
                }
                record()
            }.onFailure { Log.w(TAG, "D2_STRONG_AUTH_SNAPSHOT_FAILED", it) }
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
        val observerComparison = "d2_only"
        val snapshot = JSONObject()
            .put("version", 4)
            .put("observerComparison", observerComparison).put("pid", Process.myPid())
            .put("boot", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1))
            .put("elapsed", SystemClock.elapsedRealtime())
            .put("scheduler", scheduler).put("schedulerDetail", schedulerDetail).put("reasonObserver", reasons)
            .put("scheduleCallbackScope", "user_0_completed_calls").put("featureVerified", rewrites.get() > 0 || reasonDowngrades.get() > 0 || timeoutMechanism == "setTimeoutMs" || timeoutMechanism == "mStrongAuthTimeoutMs")
            .put("verificationMeaning", "observed_policy_intervention_not_future_enforcement")
            .put("allScheduleCallbacks", allScheduleCallbacks.get())
            .put("refreshRequests", refreshRequests.get()).put("refreshApplied", refreshApplied.get())
            .put("lastRefreshElapsed", lastRefreshElapsed)
            .put("scheduleCallbacks", schedules.get()).put("reasonCallbacks", requests.get())
            .put("lastRequest", lastRequest).put("flags", flags).put("credential", credential)
            .put("policyHours", policyHours).put("policyLabel", policyRead)
            .put("policyTimeoutMs", PeriodicCredentialPolicy.timeoutMs(policyHours))
            .put("timeoutRewrites", rewrites.get())
            .put("timeoutMechanism", timeoutMechanism)
            .put("reflectionFailures", reflectionFailures.get())
            .put("reflectionDiagnostic", reflectionDiagnostic)
            .put("lastSchedulerElapsed", lastSchedulerElapsed)
            .put("schedulerTargetClass", schedulerTargetClass)
            .put("reasonDowngrades", reasonDowngrades.get())
        Settings.Global.putString(app.contentResolver, STATUS, snapshot.toString())
        // Explicit system-origin signal; widget reads the trusted stored snapshot.
        app.sendBroadcast(android.content.Intent("app.d2lock.widget.PERIODIC_STATUS")
            .setComponent(android.content.ComponentName("app.d2lock", "app.d2lock.widget.D2Widget")))
        Log.i(TAG, "D2_STRONG_AUTH_SNAPSHOT $snapshot stock_retained=true")
    }
}
