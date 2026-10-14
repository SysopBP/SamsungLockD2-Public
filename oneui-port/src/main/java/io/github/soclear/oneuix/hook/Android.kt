/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog

@SuppressLint("PrivateApi")
object Android {
    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun disableWritingToolkitGlobally() {
        val galaxyAiRestrictionsPackage = "com.samsung.android.knox.galaxyai"
        val writingToolkitKey = "key_writing_toolkit"
        val grayoutKey = "grayout"

        val classLoader = param.classLoader

        try {
            val proxyClass = classLoader.loadClass("com.android.server.enterprise.EDMProxyService")
            proxyClass.declaredMethods.filter {
                it.name == "getApplicationRestrictions"
            }.forEach {
                xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    if (chain.args.getOrNull(0) != galaxyAiRestrictionsPackage) {
                        result
                    } else {
                        val restrictions = Bundle(result as? Bundle ?: Bundle.EMPTY)
                        val writingToolkit = Bundle(restrictions.getBundle(writingToolkitKey) ?: Bundle.EMPTY)
                        writingToolkit.putBoolean(grayoutKey, true)
                        restrictions.putBundle(writingToolkitKey, writingToolkit)
                        restrictions
                    }
                }
            }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    @SuppressLint("BlockedPrivateApi")
    context(xposedModule: XposedModule)
    fun setBlockableNotificationChannel() {
        try {
            val notificationChannelClass = NotificationChannel::class.java

            notificationChannelClass.declaredConstructors.forEach {
                xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject.reflect["mBlockableSystem"] = true
                    chain.thisObject.reflect["mImportanceLockedByOEM"] = false
                    chain.thisObject.reflect["mImportanceLockedDefaultApp"] = false
                    result
                }
            }

            notificationChannelClass
                .getDeclaredMethod("setBlockable", Boolean::class.javaPrimitiveType)
                .let { xposedModule.hook(it) }
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = true
                    chain.proceed(newArgs)
                }

            notificationChannelClass
                .getDeclaredMethod("setImportanceLockedByOEM", Boolean::class.javaPrimitiveType)
                .let { xposedModule.hook(it) }
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = false
                    chain.proceed(newArgs)
                }

            notificationChannelClass
                .getDeclaredMethod("setImportanceLockedByCriticalDeviceFunction", Boolean::class.javaPrimitiveType)
                .let { xposedModule.hook(it) }
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = false
                    chain.proceed(newArgs)
                }
        } catch (t: Throwable) {
            xlog(t)
        }
    }


    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun setMaxNeverKilledAppNum(num: Int) {
        try {
            param.classLoader.loadClass("com.android.server.am.DynamicHiddenApp").reflect["MAX_NEVERKILLEDAPP_NUM"] =
                num
        } catch (t: Throwable) {
            xlog(t)
        }
    }


    // 解除国行/港版对 GMS（含 FCM 推送）的网络限制
    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun liftFcmNetworkLimit() {
        try {
            param.classLoader
                .loadClass("com.android.server.alarm.GmsAlarmManager")
                .constructors
                .forEach {
                    xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result = chain.proceed()
                        chain.thisObject.reflect["isChinaMode"] = false
                        chain.thisObject.reflect["isHongKongMode"] = false
                        result
                    }
                }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    // OneUIX scheduler opt-out restored 2026-10-14; D2 adds runtime telemetry.
    @Volatile var periodicCredentialState: String = "disabled"
        private set
    @Volatile var periodicCredentialMethods: String = ""
        private set
    @Volatile var periodicCredentialObserver: (() -> Unit)? = null
    private val periodicCredentialSkipped = java.util.concurrent.atomic.AtomicInteger()
    fun periodicCredentialStatus(): org.json.JSONObject = org.json.JSONObject()
        .put("state", periodicCredentialState).put("methods", periodicCredentialMethods)
        .put("suppressedSchedules", periodicCredentialSkipped.get())

    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun disablePinVerifyPer72h() {
        try {
            val methods = param.classLoader
                .loadClass("com.android.server.locksettings.LockSettingsStrongAuth")
                .declaredMethods.filter { it.name == "rescheduleStrongAuthTimeoutAlarm" }
            periodicCredentialMethods = methods.joinToString { it.toGenericString() }
            check(methods.isNotEmpty()) { "No rescheduleStrongAuthTimeoutAlarm method found" }
            // Match upstream by name rather than assuming Samsung's argument layout.
            check(methods.all { it.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers) })
            methods.forEach { method ->
                xposedModule.hook(method).intercept {
                    val count = periodicCredentialSkipped.incrementAndGet()
                    runCatching { periodicCredentialObserver?.invoke() }
                    if (count <= 3) android.util.Log.i("D2PeriodicAuth", "D2_ONEUI_PERIODIC_SUPPRESSED count=$count")
                    null
                }
            }
            periodicCredentialState = "installed"
            android.util.Log.i("D2PeriodicAuth", "D2_ONEUI_PERIODIC_INSTALLED methods=$periodicCredentialMethods")
        } catch (t: Throwable) {
            periodicCredentialState = "unavailable: ${t.javaClass.simpleName}: ${t.message}"
            xlog(t)
        }
    }

    // 移除充电器时禁止亮屏
    // PowerManagerService.updateIsPoweredLocked 在插拔充电器时会调用 wakePowerGroupLocked 点亮屏幕，
    // 唤醒理由字符串为 "android.server.power:PLUGGED:" + mIsPowered。
    // 拔出充电器时 mIsPowered 为 false，拦截该次唤醒即可（插入仍正常亮屏）。
    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun disableScreenWakeOnPowerUnplugged() {
        try {
            param.classLoader
                .loadClass("com.android.server.power.PowerManagerService")
                .declaredMethods
                .filter { it.name == "wakePowerGroupLocked" }
                .forEach {
                    xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        if (chain.args.getOrNull(3) == "android.server.power:PLUGGED:false") {
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                }
        } catch (t: Throwable) {
            xlog(t)
        }
    }
}
