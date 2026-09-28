package app.d2lock.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * - Preserve the proven ActivityManagerService.systemReady handoff.
 * - Experimentally observe wakeUpInternal after Android handles it.
 * - Do not hook SystemUI, keyguard, biometrics, lock settings or ATMS.
 * - Never change framework arguments/results.
 * - Fail open if the hook or D2 signal fails.
 *
 * This is an isolated diagnostic build. Keep the minimal release build as a
 * rollback until a real device has completed the boot and wake test.
 */
class GuardianXposedBridge : XposedModule() {
    companion object {
        private const val TAG = "D2XposedBridge"
        private val bootReadySent = AtomicBoolean(false)
        private val lastWakeSignalMs = AtomicLong(0L)
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
        installWakeObservation(param.classLoader)
    }

    private fun installWakeObservation(classLoader: ClassLoader) {
        val className = "com.android.server.power.PowerManagerService"
        val owner = runCatching { Class.forName(className, false, classLoader) }
            .getOrElse {
                log(Log.WARN, TAG, "GUARDIAN_WAKE_CLASS_MISSING target=$className", it)
                return
            }
        val methods = owner.declaredMethods.filter { it.name == "wakeUpInternal" }
        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_UNAVAILABLE target=$className#wakeUpInternal")
            return
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (bootReadySent.get()) {
                            runCatching {
                                val now = SystemClock.elapsedRealtime()
                                val previous = lastWakeSignalMs.get()
                                if (now - previous > 750L && lastWakeSignalMs.compareAndSet(previous, now)) {
                                    Handler(Looper.getMainLooper()).post { signalWakeObserved() }
                                }
                            }.onFailure { log(Log.WARN, TAG, "GUARDIAN_WAKE_OBSERVE_FAILED", it) }
                        }
                        result
                    }
                log(Log.INFO, TAG, "GUARDIAN_WAKE_HOOK_INSTALLED target=$className#${method.name}")
            }.onFailure {
                log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_FAILED target=$className#${method.name}", it)
            }
        }
    }

    private fun signalWakeObserved() {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: error("system_server application unavailable")
            app.sendBroadcast(
                Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                    .putExtra("event", "WAKE_OBSERVED")
                    .putExtra("method", "wakeUpInternal")
                    .putExtra("source", "com.android.server.power.PowerManagerService#wakeUpInternal")
            )
            log(Log.INFO, TAG, "GUARDIAN_WAKE_OBSERVED_SENT")
        }.onFailure { log(Log.WARN, TAG, "GUARDIAN_WAKE_SIGNAL_FAILED", it) }
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
