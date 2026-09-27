package app.d2lock.xposed

import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val SAMSUNG_BIOMETRICS = "com.samsung.android.biometrics.app.setting"
        private val heartbeatStarted = AtomicBoolean(false)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_743_LOADED api=$apiVersion framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == SAMSUNG_BIOMETRICS) {
            log(Log.INFO, TAG, "GUARDIAN_XPOSED_743_BIOMETRICS_READY package=${param.packageName}")
            return
        }
        if (param.packageName != SYSTEM_UI) return

        log(Log.INFO, TAG, "GUARDIAN_XPOSED_743_SYSTEMUI_READY package=${param.packageName}")
        installPowerDiagnostics()
        installSystemUiNotificationDiagnostics(param.classLoader)
        installGuardianSystemUiHooks(param.classLoader)
        installKeyguardHandoffHooks(param.classLoader)
        installFingerprintStateDiagnostics(param.classLoader)
        installFingerprintAuthEventBridge(param.classLoader)
    }

    /**
     * Stage 3a: observe SystemUI's notification pipeline without changing it.
     *
     * Samsung/AOSP SystemUI internals can move between releases, so discover the
     * pipeline class from a small compatibility list and hook every matching
     * add/update/remove method that carries a StatusBarNotification. This remains
     * read-only: the original SystemUI method always proceeds unchanged.
     */

    /**
     * Stage 3b: broad read-only SystemUI discovery hooks. These are deliberately
     * observational: no result is replaced and every original method proceeds.
     * Samsung moves implementation classes between One UI releases, so each
     * feature has multiple candidates and silently skips unavailable targets.
     */
    private fun installGuardianSystemUiHooks(classLoader: ClassLoader) {
        installNamedProbes(classLoader, "SCREEN",
            listOf(
                "com.android.systemui.keyguard.WakefulnessLifecycle" to listOf("dispatchStartedWakingUp", "dispatchFinishedWakingUp", "dispatchStartedGoingToSleep", "dispatchFinishedGoingToSleep"),
                "com.android.systemui.keyguard.ScreenLifecycle" to listOf("dispatchScreenTurningOn", "dispatchScreenTurnedOn", "dispatchScreenTurningOff", "dispatchScreenTurnedOff")
            ))
        installNamedProbes(classLoader, "FINGERPRINT",
            listOf(
                "com.android.keyguard.KeyguardUpdateMonitor" to listOf("onBiometricAuthenticated", "onBiometricAuthFailed", "onBiometricAcquired", "onBiometricError", "handleFingerprintAuthenticated", "handleFingerprintAuthFailed", "onFingerprintAuthenticated", "onFingerprintAuthFailed", "handleFingerprintAcquired", "handleFingerprintError"),
                "com.android.systemui.biometrics.AuthController" to listOf("onBiometricAuthenticated", "onBiometricError", "onBiometricHelp", "onFingerprintAuthenticated"),
                "com.android.systemui.biometrics.UdfpsController" to listOf("onFingerDown", "onFingerUp", "onAcquired", "onAuthenticated"),
                "com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepositoryImpl" to listOf("onAuthenticationSucceeded", "onAuthenticationFailed", "onAuthenticationError", "onAuthenticationAcquired"),
                "com.android.systemui.keyguard.domain.interactor.DeviceEntryFingerprintAuthInteractor" to listOf("onAuthenticationSucceeded", "onAuthenticationFailed")
            ))
        installNamedProbes(classLoader, "SHADE_BARS",
            listOf(
                "com.android.systemui.statusbar.CommandQueue" to listOf("animateCollapsePanels", "animateExpandNotificationsPanel", "animateExpandSettingsPanel"),
                "com.android.systemui.shade.NotificationPanelViewController" to listOf("expand", "collapse", "fling")
            ))
        installNamedProbes(classLoader, "MEDIA",
            listOf(
                "com.android.systemui.media.controls.pipeline.MediaDataManager" to listOf("onNotificationAdded", "onNotificationRemoved", "onNotificationUpdated", "setTimedOut")
            ))
        installNamedProbes(classLoader, "CHARGING",
            listOf(
                "com.android.systemui.statusbar.policy.BatteryControllerImpl" to listOf("fireBatteryLevelChanged", "firePowerSaveChanged", "onReceive")
            ))
        installNamedProbes(classLoader, "CALL",
            listOf(
                "com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController" to listOf("updateChip", "removeChip"),
                "com.android.systemui.statusbar.phone.ongoingcall.OngoingCallControllerImpl" to listOf("updateChip", "removeChip")
            ))
        installNamedProbes(classLoader, "HOME_RECENTS",
            listOf(
                "com.android.systemui.recents.OverviewProxyService" to listOf("onConnectionChanged", "notifyToggleRecentApps", "notifyOverviewShown")
            ))
        installNamedProbes(classLoader, "GESTURE",
            listOf(
                "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler" to listOf("onMotionEvent", "onNavBarAttached", "onNavBarDetached")
            ))
        installNamedProbes(classLoader, "SYSTEMUI_RECOVERY",
            listOf(
                "com.android.systemui.SystemUIApplication" to listOf("onCreate", "startServicesIfNeeded"),
                "com.android.systemui.SystemUIService" to listOf("onCreate")
            ))
    }

    /**
     * Build 744: turn the already-proven SystemUI keyguard probes into a narrow
     * state signal for Guardian. No SystemUI return value is changed.
     */
    private fun installKeyguardHandoffHooks(classLoader: ClassLoader) {
        val targets = listOf(
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl" to listOf("notifyKeyguardGoingAway", "notifyKeyguardState"),
            "com.android.systemui.keyguard.KeyguardViewMediator" to listOf("showLocked", "hideLocked")
        )
        var installed = 0
        targets.forEach { (className, methodNames) ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: return@forEach
            owner.declaredMethods.filter { it.name in methodNames }.distinctBy { it.toGenericString() }.forEach { method ->
                try {
                    method.isAccessible = true
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result = chain.proceed()
                        val state = when (method.name) {
                            "showLocked" -> "SHOWING"
                            "notifyKeyguardGoingAway" -> "GOING_AWAY"
                            "hideLocked" -> "CLEAR"
                            else -> "STATE_CHANGED"
                        }
                        log(Log.INFO, TAG, "GUARDIAN_XPOSED_KEYGUARD_SIGNAL state=$state target=${owner.name}#${method.name}")
                        signalGuardianKeyguard(state, owner.name + "#" + method.name)
                        result
                    }
                    installed++
                    log(Log.INFO, TAG, "GUARDIAN_XPOSED_KEYGUARD_HANDOFF_HOOK_INSTALLED target=${owner.name}#${method.name}")
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "GUARDIAN_XPOSED_KEYGUARD_HANDOFF_HOOK_FAILED target=${owner.name}#${method.name}", t)
                }
            }
        }
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_KEYGUARD_HANDOFF_READY hooks=$installed")
    }

    private fun startHeartbeat(source: String) {
        if (!heartbeatStarted.compareAndSet(false, true)) return
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_HEARTBEAT_STARTED interval=30s source=$source")
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "D2-Xposed-Heartbeat").apply { isDaemon = true }
        }.scheduleAtFixedRate({
            runCatching { signalGuardianKeyguard("HEARTBEAT", source) }
                .onFailure { log(Log.WARN, TAG, "GUARDIAN_XPOSED_HEARTBEAT_FAILED", it) }
        }, 0, 30, TimeUnit.SECONDS)
    }

    private fun signalGuardianKeyguard(state: String, source: String) {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: error("SystemUI application unavailable")
            val intent = Intent("app.d2lock.action.XPOSED_KEYGUARD_STATE")
                .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                .putExtra("state", state)
                .putExtra("source", source)
            app.sendBroadcast(intent)
        }.onFailure {
            log(Log.WARN, TAG, "GUARDIAN_XPOSED_KEYGUARD_SIGNAL_FAILED state=$state", it)
        }
    }

    /** Read-only Samsung fingerprint eligibility diagnostics. */
    /**
     * Stage 4: report genuine SystemUI fingerprint authentication events to Guardian.
     * Observational only: never changes arguments/results and never synthesizes success.
     */
    private fun installFingerprintAuthEventBridge(classLoader: ClassLoader) {
        val targets = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor" to listOf(
                "onBiometricAuthenticated", "handleFingerprintAuthenticated", "onFingerprintAuthenticated",
                "onBiometricAuthFailed", "handleFingerprintAuthFailed", "onFingerprintAuthFailed"
            ),
            "com.android.systemui.biometrics.AuthController" to listOf(
                "onBiometricAuthenticated", "onFingerprintAuthenticated"
            )
        )
        var installed = 0
        targets.forEach { (className, names) ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: return@forEach
            owner.declaredMethods.filter { it.name in names }.distinctBy { it.toGenericString() }.forEach { method ->
                try {
                    method.isAccessible = true
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result = chain.proceed()
                        val event = if (method.name.contains("Fail", true)) "FAILED" else "AUTHENTICATED"
                        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FP_AUTH_EVENT event=$event target=${owner.name}#${method.name}")
                        signalGuardianFingerprint(event, owner.name + "#" + method.name)
                        result
                    }
                    installed++
                    log(Log.INFO, TAG, "GUARDIAN_XPOSED_FP_AUTH_HOOK_INSTALLED target=${owner.name}#${method.name}")
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "GUARDIAN_XPOSED_FP_AUTH_HOOK_FAILED target=${owner.name}#${method.name}", t)
                }
            }
        }
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FP_AUTH_READY hooks=$installed")
    }

    private fun signalGuardianFingerprint(event: String, source: String) {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: error("SystemUI application unavailable")
            val intent = Intent("app.d2lock.action.XPOSED_FINGERPRINT_EVENT")
                .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                .putExtra("fingerprint_event", event)
                .putExtra("source", source)
            app.sendBroadcast(intent)
        }.onFailure { log(Log.WARN, TAG, "GUARDIAN_XPOSED_FP_AUTH_SIGNAL_FAILED event=$event", it) }
    }

    private fun installFingerprintStateDiagnostics(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.statusbar.phone.KeyguardBypassController",
            "com.android.systemui.biometrics.AuthController",
            "com.android.systemui.biometrics.UdfpsController"
        )
        val nameHints = listOf("fingerprint", "udfps", "biometric", "enrolled", "enabled", "listen", "listening", "authenticate", "start", "update")
        var installed = 0

        candidates.forEach { className ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (owner == null) {
                log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPSTATE_CLASS_MISSING target=$className")
                return@forEach
            }
            owner.declaredMethods
                .filter { method ->
                    nameHints.any { hint -> method.name.contains(hint, ignoreCase = true) } &&
                        method.name != "getBypassEnabled" &&
                        method.parameterCount <= 8
                }
                .distinctBy { it.toGenericString() }
                .forEach { method ->
                    try {
                        method.isAccessible = true
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val result = chain.proceed()
                                val resultText = when (result) {
                                    null -> "null"
                                    is Boolean, is Number, is String, is Enum<*> -> result.toString()
                                    else -> result.javaClass.name
                                }
                                val argsText = chain.args.joinToString(",") { arg ->
                                    when (arg) {
                                        null -> "null"
                                        is Boolean, is Number, is String, is Enum<*> -> arg.toString()
                                        else -> arg.javaClass.name
                                    }
                                }
                                log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPSTATE_CALL target=${owner.name}#${method.name} signature=${method.toGenericString()} args=[$argsText] result=$resultText")
                                result
                            }
                        installed++
                        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPSTATE_HOOK_INSTALLED target=${owner.name}#${method.name}")
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "GUARDIAN_XPOSED_FPSTATE_HOOK_FAILED target=${owner.name}#${method.name}", t)
                    }
                }
        }
        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPSTATE_READY hooks=$installed")
    }

    private fun installNamedProbes(
        classLoader: ClassLoader,
        event: String,
        targets: List<Pair<String, List<String>>>
    ) {
        var installed = 0
        targets.forEach { (className, methodNames) ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: return@forEach
            owner.declaredMethods
                .filter { it.name in methodNames }
                .distinctBy { it.toGenericString() }
                .forEach { method ->
                    try {
                        method.isAccessible = true
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val argsText = chain.args.joinToString(",") { arg ->
                                    when (arg) {
                                        null -> "null"
                                        is Boolean, is Number, is String, is Enum<*> -> arg.toString()
                                        else -> arg.javaClass.name
                                    }
                                }
                                log(Log.INFO, TAG, "GUARDIAN_XPOSED_${event} method=${method.name} target=${owner.name} args=[$argsText]")
                                val result = chain.proceed()
                                // SystemUI's Application is not guaranteed to exist at PackageReady.
                                // Emit the first verified bridge heartbeat only after the real
                                // SystemUI lifecycle has reached onCreate/startServicesIfNeeded.
                                if (event == "SYSTEMUI_RECOVERY" &&
                                    (method.name == "onCreate" || method.name == "startServicesIfNeeded")) {
                                    startHeartbeat(owner.name + "#" + method.name)
                                }
                                result
                            }
                        installed++
                        log(Log.INFO, TAG, "GUARDIAN_XPOSED_${event}_HOOK_INSTALLED target=${owner.name}#${method.name}")
                    } catch (t: Throwable) {
                        log(Log.ERROR, TAG, "GUARDIAN_XPOSED_${event}_HOOK_FAILED target=${owner.name}#${method.name}", t)
                    }
                }
        }
        if (installed == 0) {
            log(Log.WARN, TAG, "GUARDIAN_XPOSED_${event}_UNAVAILABLE")
        } else {
            log(Log.INFO, TAG, "GUARDIAN_XPOSED_${event}_READY hooks=$installed")
        }
    }

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
