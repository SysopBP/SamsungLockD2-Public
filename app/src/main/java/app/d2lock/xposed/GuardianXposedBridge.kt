package app.d2lock.xposed

import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * Guardian Xposed Bridge v0.2 — Stage 2 diagnostics.
 *
 * Read-only by design. Verifies that API 102 method interception works inside
 * SystemUI before Guardian uses Xposed for any enforcement.
 */
class GuardianXposedBridge : XposedModule() {
    companion object {
        private const val TAG = "D2XposedBridge"
        private const val SYSTEM_UI = "com.android.systemui"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_LOADED api=$apiVersion framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return

        log(Log.INFO, TAG, "GUARDIAN_XPOSED_SYSTEMUI_READY package=${param.packageName}")
        installPowerDiagnostics()
        installSystemUiNotificationDiagnostics(param.classLoader)
    }

    /**
     * Stage 3a: observe SystemUI's notification pipeline without changing it.
     *
     * Samsung/AOSP SystemUI internals can move between releases, so discover the
     * pipeline class from a small compatibility list and hook every matching
     * add/update/remove method that carries a StatusBarNotification. This remains
     * read-only: the original SystemUI method always proceeds unchanged.
     */
    private fun installSystemUiNotificationDiagnostics(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.systemui.statusbar.notification.collection.NotifCollection",
            "com.android.systemui.statusbar.notification.NotificationEntryManager"
        )
        var installed = 0

        candidates.forEach { className ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: return@forEach

            owner.declaredMethods
                .filter { method ->
                    method.parameterTypes.any { StatusBarNotification::class.java.isAssignableFrom(it) } &&
                        (method.name.contains("add", true) ||
                            method.name.contains("update", true) ||
                            method.name.contains("remove", true) ||
                            method.name.contains("post", true))
                }
                .distinctBy { it.toGenericString() }
                .forEach { method ->
                    try {
                        method.isAccessible = true
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                                if (sbn != null) {
                                    val action = when {
                                        method.name.contains("remove", true) -> "REMOVED"
                                        method.name.contains("update", true) -> "UPDATED"
                                        else -> "POSTED"
                                    }
                                    log(
                                        Log.INFO,
                                        TAG,
                                        "GUARDIAN_XPOSED_ISLAND_NOTIFICATION action=$action package=${sbn.packageName} key=${sbn.key}"
                                    )
                                }
                                chain.proceed()
                            }
                        installed++
                        log(
                            Log.INFO,
                            TAG,
                            "GUARDIAN_XPOSED_ISLAND_HOOK_INSTALLED target=${owner.name}#${method.name}"
                        )
                    } catch (t: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "GUARDIAN_XPOSED_ISLAND_HOOK_FAILED target=${owner.name}#${method.name}",
                            t
                        )
                    }
                }
        }

        if (installed == 0) {
            log(
                Log.WARN,
                TAG,
                "GUARDIAN_XPOSED_ISLAND_NOTIFICATION_UNAVAILABLE reason=no_compatible_systemui_pipeline"
            )
        } else {
            log(Log.INFO, TAG, "GUARDIAN_XPOSED_ISLAND_NOTIFICATION_READY hooks=$installed")
        }
    }

    private fun installPowerDiagnostics() {
        installBooleanProbe(PowerManager::class.java, "isInteractive", "INTERACTIVE")
        installBooleanProbe(PowerManager::class.java, "isPowerSaveMode", "POWER_SAVE")
    }

    private fun installBooleanProbe(owner: Class<*>, methodName: String, event: String) {
        try {
            val method: Method = owner.getDeclaredMethod(methodName)
            method.isAccessible = true

            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    log(Log.INFO, TAG, "GUARDIAN_XPOSED_${event} value=$result")
                    result
                }

            log(Log.INFO, TAG, "GUARDIAN_XPOSED_HOOK_INSTALLED target=${owner.name}#$methodName")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "GUARDIAN_XPOSED_HOOK_FAILED target=${owner.name}#$methodName", t)
        }
    }
}
