package app.d2lock.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Guardian Xposed Bridge — verified minimal Android 17 boot handoff.
 *
 * Purpose:
 * - Preserve D2's UID 1000/system-app design.
 * - Keep only the proven ActivityManagerService.systemReady handoff.
 * - Do not hook SystemUI, keyguard, biometrics, lock settings, ATMS or power.
 * - Never change framework arguments/results.
 * - Fail open if the hook or D2 signal fails.
 *
 * This deliberately removes the previous diagnostic/enforcement hook surface so
 * This is the release baseline validated across five consecutive reboots on the\n * SM-S948U1 Android 17 test device. Do not expand the system_server hook surface\n * without isolated boot testing.
 */
class GuardianXposedBridge : XposedModule() {
    companion object {
        private const val TAG = "D2XposedBridge"
        private val bootReadySent = AtomicBoolean(false)
        private val lastWakeSignalMs = AtomicLong(0L)
        private const val WAKE_DEDUPE_MS = 750L
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_MINIMAL_LOADED api=$apiVersion framework=$frameworkName mode=ams_systemReady_plus_wake_observe"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_MINIMAL_SYSTEM_SERVER_START uid=${android.os.Process.myUid()}"
        )
        installBootReadyHook(param.classLoader)
        installWakeObserver(param.classLoader)
    }

    private fun installBootReadyHook(classLoader: ClassLoader) {
        val className = "com.android.server.am.ActivityManagerService"
        val owner = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrElse {
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_CLASS_MISSING target=$className", it)
            return
        }

        val methods = owner.declaredMethods
            .filter { it.name == "systemReady" }
            .distinctBy { it.toGenericString() }

        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_HOOK_UNAVAILABLE target=$className#systemReady")
            return
        }

        methods.forEach { method ->
            try {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        // Android/system_server always gets control first. D2 is signalled
                        // only after the real systemReady invocation returns successfully.
                        val result = chain.proceed()
                        signalBootReady()
                        result
                    }

                log(
                    Log.INFO,
                    TAG,
                    "GUARDIAN_MINIMAL_HOOK_INSTALLED target=${owner.name}#${method.name}"
                )
            } catch (t: Throwable) {
                // Fail open: a bridge problem must never intentionally block boot.
                log(
                    Log.WARN,
                    TAG,
                    "GUARDIAN_MINIMAL_HOOK_FAILED target=${owner.name}#${method.name}",
                    t
                )
            }
        }
    }


    /**
     * Android 17 on the SM-S948U1 no longer exposes the older
     * PowerManagerService#wakeUpInternal signature. Observe the public BinderService
     * wake entry points that are actually present instead. This hook is deliberately
     * post-call and never changes arguments/results, so Android remains authoritative.
     */
    private fun installWakeObserver(classLoader: ClassLoader) {
        val className = "com.android.server.power.PowerManagerService\$BinderService"
        val owner = runCatching { Class.forName(className, false, classLoader) }.getOrElse {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_CLASS_MISSING target=$className", it)
            return
        }
        val methods = owner.declaredMethods
            .filter { it.name == "wakeUp" || it.name == "wakeUpWithDisplayId" }
            .distinctBy { it.toGenericString() }

        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_UNAVAILABLE target=$className#wakeUp*")
            return
        }

        methods.forEach { method ->
            try {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        signalWakeObserved(method.name, owner.name)
                        result
                    }
                log(Log.INFO, TAG, "GUARDIAN_WAKE_HOOK_INSTALLED target=${owner.name}#${method.name}")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_FAILED target=${owner.name}#${method.name}", t)
            }
        }
    }

    private fun signalWakeObserved(method: String, source: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastWakeSignalMs.get()
        if (now - previous < WAKE_DEDUPE_MS || !lastWakeSignalMs.compareAndSet(previous, now)) return

        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
                ?: error("system_server application unavailable")
            app.sendBroadcast(
                Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                    .putExtra("event", "WAKE")
                    .putExtra("method", method)
                    .putExtra("source", "$source#$method")
            )
            log(Log.INFO, TAG, "GUARDIAN_WAKE_SENT method=$method")
        }.onFailure {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_SIGNAL_FAILED method=$method", it)
        }
    }

    private fun signalBootReady() {
        if (!bootReadySent.compareAndSet(false, true)) {
            log(Log.DEBUG, TAG, "GUARDIAN_MINIMAL_BOOT_READY_DEDUPED")
            return
        }

        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
                ?: error("system_server application unavailable")

            app.sendBroadcast(
                Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(
                        ComponentName(
                            "app.d2lock",
                            "app.d2lock.lockscreen.KeyguardSignalReceiver"
                        )
                    )
                    .putExtra("event", "BOOT_READY")
                    .putExtra("method", "systemReady")
                    .putExtra(
                        "source",
                        "com.android.server.am.ActivityManagerService#systemReady"
                    )
            )

            log(Log.INFO, TAG, "GUARDIAN_MINIMAL_BOOT_READY_SENT")
        }.onFailure {
            // No retry loop or heartbeat. If signalling fails, leave Android alone.
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_BOOT_READY_SIGNAL_FAILED", it)
        }
    }
}
