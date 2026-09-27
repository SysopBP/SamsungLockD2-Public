package app.d2lock.xposed

import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
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

    /**
     * Stage 2c: system_server observation bridge.
     *
     * Deliberately read-only. D2 remains a normal application UID; libxposed injects
     * this entry into system_server (UID 1000) when the static "system" scope is enabled.
     * No return values or arguments are modified in this stage.
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "GUARDIAN_SYS_READY uid=${android.os.Process.myUid()} process=system_server")
        installSystemServerDiagnostics(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return

        log(Log.INFO, TAG, "GUARDIAN_XPOSED_SYSTEMUI_READY package=${param.packageName}")
        installPowerDiagnostics()
        installSystemUiNotificationDiagnostics(param.classLoader)
        installGuardianSystemUiHooks(param.classLoader)
        installFingerprintStateDiagnostics(param.classLoader)
        installFingerprintEnrollmentDiagnostics(param.classLoader)
    }


    /**
     * Small, compatibility-oriented probes for the services involved in the D2/keyguard
     * race. We intentionally log only target + method here: credential/biometric arguments
     * can contain security-sensitive framework objects and must never be dumped.
     */
    private fun installSystemServerDiagnostics(classLoader: ClassLoader) {
        val targets = listOf(
            "com.android.server.locksettings.LockSettingsService" to listOf(
                "systemReady", "verifyCredential", "setLockCredential",
                "getCredentialType", "getStrongAuthForUser"
            ),
            "com.android.server.biometrics.BiometricService" to listOf(
                "onStart", "authenticate", "cancelAuthentication",
                "registerAuthenticator", "getCurrentStrength"
            ),
            "com.android.server.wm.KeyguardController" to listOf(
                "setKeyguardShown", "keyguardGoingAway",
                "dismissKeyguard", "isKeyguardLocked"
            ),
            "com.android.server.wm.ActivityTaskManagerService" to listOf(
                "keyguardGoingAway", "setLockScreenShown"
            ),
            "com.android.server.power.PowerManagerService" to listOf(
                "wakeUpInternal", "goToSleepInternal"
            )
        )

        var installed = 0
        targets.forEach { (className, methodNames) ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (owner == null) {
                log(Log.INFO, TAG, "GUARDIAN_SYS_CLASS_MISSING target=$className")
                return@forEach
            }

            owner.declaredMethods
                .filter { it.name in methodNames }
                .distinctBy { it.toGenericString() }
                .forEach { method ->
                    try {
                        method.isAccessible = true
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                log(
                                    Log.INFO,
                                    TAG,
                                    "GUARDIAN_SYS_CALL target=${owner.name}#${method.name}"
                                )
                                chain.proceed()
                            }
                        installed++
                        log(
                            Log.INFO,
                            TAG,
                            "GUARDIAN_SYS_HOOK_INSTALLED target=${owner.name}#${method.name}"
                        )
                    } catch (t: Throwable) {
                        log(
                            Log.WARN,
                            TAG,
                            "GUARDIAN_SYS_HOOK_FAILED target=${owner.name}#${method.name}",
                            t
                        )
                    }
                }
        }

        if (installed == 0) {
            log(Log.WARN, TAG, "GUARDIAN_SYS_UNAVAILABLE reason=no_compatible_framework_targets")
        } else {
            log(Log.INFO, TAG, "GUARDIAN_SYS_ACTIVE hooks=$installed mode=observe_only")
        }
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
                "com.android.keyguard.KeyguardUpdateMonitor" to listOf("onBiometricAuthenticated", "onBiometricAuthFailed", "onBiometricAcquired", "onBiometricError", "handleFingerprintAuthenticated", "handleFingerprintAuthFailed"),
                "com.android.systemui.biometrics.AuthController" to listOf("onBiometricAuthenticated", "onBiometricError", "onBiometricHelp"),
                "com.android.systemui.biometrics.UdfpsController" to listOf("onFingerDown", "onFingerUp", "onAcquired")
            ))
        installNamedProbes(classLoader, "KEYGUARD",
            listOf(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl" to listOf("notifyKeyguardState", "notifyKeyguardGoingAway"),
                "com.android.systemui.keyguard.KeyguardViewMediator" to listOf("showLocked", "hideLocked", "onStartedGoingToSleep", "onStartedWakingUp")
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

    /** Read-only Samsung fingerprint eligibility diagnostics. */
    private fun installFingerprintStateDiagnostics(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.statusbar.phone.KeyguardBypassController",
            "com.android.systemui.biometrics.AuthController",
            "com.android.systemui.biometrics.UdfpsController"
        )
        val nameHints = listOf("fingerprint", "udfps", "biometric", "enrolled", "enabled", "listen", "listening", "authenticate")
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
                        method.parameterCount <= 6
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
                                log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPSTATE_CALL target=${owner.name}#${method.name} args=[$argsText] result=$resultText")
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

    /**
     * Stage 2b: trace the enrollment/authenticator boundary without modifying it.
     *
     * The boot logs show a live UDFPS sensor while SystemUI reports no enrolled
     * fingerprint. These probes tell us whether that state originates in the
     * framework FingerprintManager or only in Samsung/SystemUI policy.
     */
    private fun installFingerprintEnrollmentDiagnostics(classLoader: ClassLoader) {
        val candidates = listOf(
            "android.hardware.fingerprint.FingerprintManager",
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.biometrics.AuthController"
        )
        val nameHints = listOf(
            "enrolled", "enrollment", "authenticator", "fingerprint",
            "strongauth", "supported", "possible"
        )
        var installed = 0

        candidates.forEach { className ->
            val owner = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (owner == null) {
                log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPENROLL_CLASS_MISSING target=$className")
                return@forEach
            }

            owner.declaredMethods
                .filter { method ->
                    nameHints.any { hint -> method.name.contains(hint, ignoreCase = true) } &&
                        method.parameterCount <= 6
                }
                .distinctBy { it.toGenericString() }
                .forEach { method ->
                    try {
                        method.isAccessible = true
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val result = chain.proceed()
                                val resultText = summarizeDiagnosticValue(result)
                                val argsText = chain.args.joinToString(",") { summarizeDiagnosticValue(it) }
                                log(
                                    Log.INFO,
                                    TAG,
                                    "GUARDIAN_XPOSED_FPENROLL_CALL target=${owner.name}#${method.name} args=[$argsText] result=$resultText"
                                )
                                result
                            }
                        installed++
                        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPENROLL_HOOK_INSTALLED target=${owner.name}#${method.name}")
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "GUARDIAN_XPOSED_FPENROLL_HOOK_FAILED target=${owner.name}#${method.name}", t)
                    }
                }
        }

        log(Log.INFO, TAG, "GUARDIAN_XPOSED_FPENROLL_READY hooks=$installed")
    }

    private fun summarizeDiagnosticValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number, is String, is Enum<*> -> value.toString()
        is Collection<*> -> "${value.javaClass.name}(size=${value.size})"
        is Array<*> -> "${value.javaClass.name}(size=${value.size})"
        is IntArray -> "IntArray(size=${value.size})"
        is LongArray -> "LongArray(size=${value.size})"
        else -> value.javaClass.name
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
                                chain.proceed()
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
