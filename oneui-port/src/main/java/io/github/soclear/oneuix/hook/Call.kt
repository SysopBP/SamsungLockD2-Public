/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.HookConfig
import io.github.soclear.oneuix.hook.util.afterAttach
import io.github.soclear.oneuix.hook.util.getHookConfig
import io.github.soclear.oneuix.hook.util.longVersionCode
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object Call {
    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun supportVoiceCallRecording(preferRecordingButton: Boolean) {
        if (param.packageName != Package.TELEPHONYUI &&
            param.packageName != Package.INCALLUI &&
            param.packageName != Package.DIALER
        ) return

        try {
            val semCscFeatureClass =
                param.classLoader.loadClass("com.samsung.android.feature.SemCscFeature")
            semCscFeatureClass.declaredMethods
                .filter { it.name == "getString" }
                .forEach { method ->
                    xposedModule.hook(method).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        if (chain.args.firstOrNull() == "CscFeature_VoiceCall_ConfigRecording") {
                            "RecordingAllowed" + if (preferRecordingButton) "" else "ByMenu"
                        } else {
                            chain.proceed()
                        }
                    }
                }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun showGeocodedLocationInRecentCall() {
        if (param.packageName != Package.DIALER) return
        afterAttach {
            // D2 modification 2026-10-14: discovery failures must retain the stock Dialer.
            try {
                val hookConfig = getHookConfig { getHookConfigFromDexKit() } ?: return@afterAttach
                val methodInstance =
                    DexMethod(hookConfig.setSubTextMethod).getMethodInstance(classLoader)
                val geoCodedLocationField =
                    DexField(hookConfig.geoCodedLocationField).getFieldInstance(classLoader)
                val subTextField =
                    DexField(hookConfig.subTextField).getFieldInstance(classLoader)

                xposedModule.hook(methodInstance).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    // Samsung Dialer may change this call signature between builds.
                    // Preserve the stock row when the expected model objects are absent.
                    val baseCallLog = chain.args.getOrNull(0)
                    val callLogViewItem = chain.args.getOrNull(1)
                    if (baseCallLog != null && callLogViewItem != null &&
                        geoCodedLocationField.declaringClass.isInstance(baseCallLog) &&
                        subTextField.declaringClass.isInstance(callLogViewItem)) {
                        runCatching {
                            val location = (geoCodedLocationField.get(baseCallLog) as? String)
                                ?.trim()?.takeIf { it.isNotEmpty() }
                            val existing = (subTextField.get(callLogViewItem) as? String).orEmpty()
                            if (location != null && !existing.contains(location)) {
                                subTextField.set(callLogViewItem, "$existing $location".trim())
                            }
                        }.onFailure { xlog(it) }
                    }
                    result
                }
            } catch (t: Throwable) {
                xlog(t)
            }
        }
    }

    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun isOpStyleCHN() {
        if (param.packageName != Package.DIALER) return
        try {
            val clazz =
                param.classLoader.loadClass("com.samsung.android.dialtacts.util.CscFeatureUtil")
            val method = clazz.getDeclaredMethod("isOpStyleCHNImpl")
            xposedModule.hook(method).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { true }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    @Serializable
    private data class CallHookConfig(
        override val versionCode: Long,
        val geoCodedLocationField: String,
        val subTextField: String,
        val setSubTextMethod: String,
    ) : HookConfig

    private fun Context.getHookConfigFromDexKit(): CallHookConfig? {
        val exclusions = listOf(
            "android",
            "androidx",
            "appfunctions_aggregated_deps",
            "com",
            "dagger",
            "kotlin",
            "kotlinx"
        )

        fun geoCodedLocation(bridge: DexKitBridge): Field? {
            val baseCallLogClassData = bridge.findClass {
                excludePackages(exclusions)
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.FINAL
                    superClass = "java.lang.Object"
                    usingStrings("BaseCallLog(id=")
                }
            }.singleOrNull() ?: return null
            val baseCallLogClass = baseCallLogClassData.getInstance(classLoader)
            // New Samsung obfuscations may have no zero-argument constructor.
            // Do not guess constructor arguments or allocate an uninitialized framework model.
            val constructor = baseCallLogClass.declaredConstructors.singleOrNull { it.parameterCount == 0 }
            if (constructor == null) {
                android.util.Log.w("D2OneUIX", "D2_DIALER_LOCATION_UNAVAILABLE reason=no_zero_arg_constructor stock_retained=true")
                return null
            }
            val instance = constructor.apply { isAccessible = true }.newInstance()

            val tokenToField = mutableMapOf<String, Field>()

            baseCallLogClass.declaredFields.forEach { field ->
                if (field.type == String::class.java && !Modifier.isStatic(field.modifiers)) {
                    field.isAccessible = true
                    val token = "TOKEN_${field.name}"

                    // 将 token 注入到实例中
                    field.set(instance, token)
                    tokenToField[token] = field
                }
            }

            // 调用 toString() 方法
            // 目标代码： ... + ", geoCodedLocation=" + this.p + ...
            val result = instance.toString()

            // 分析结果
            val keyword = "geoCodedLocation="
            val index = result.indexOf(keyword)
            if (index != -1) {
                // 截取 geoCodedLocation= 之后的内容
                val after = result.substring(index + keyword.length)

                // 检查内容是以哪个 TOKEN 开头
                for (entry in tokenToField.entries) {
                    if (after.startsWith(entry.key)) {
                        // 找到了 geoCodedLocation 对应字段
                        return entry.value
                    }
                }
            }
            return null
        }

        fun subText(bridge: DexKitBridge): Field? {
            val callLogViewItemClassData = bridge.findClass {
                excludePackages(exclusions)
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.FINAL
                    superClass = "java.lang.Object"
                    usingStrings("CallLogViewItem {id=")
                }
            }.singleOrNull() ?: return null

            val toStingMethodData = callLogViewItemClassData.findMethod {
                matcher {
                    usingStrings("CallLogViewItem {id=")
                }
            }.singleOrNull() ?: return null

            val callLogGroupClassData = bridge.findClass {
                excludePackages(exclusions)
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.FINAL
                    superClass = "java.lang.Object"
                    usingStrings("CallLogGroup(isDateChanged=")
                }
            }.singleOrNull() ?: return null

            val callLogGroupClass = callLogGroupClassData.getInstance(classLoader)
            val callLogGroup = callLogGroupClass.reflect.new(true, true)

            val callLogViewItemClass = callLogViewItemClassData.getInstance(classLoader)
            val callLogViewItem = callLogViewItemClass.reflect.new(callLogGroup)

            val tokenToField = mutableMapOf<String, Field>()

            callLogViewItemClass.declaredFields.forEach { field ->
                if (field.type == String::class.java && !Modifier.isStatic(field.modifiers)) {
                    field.isAccessible = true
                    val token = "TOKEN_${field.name}"

                    // 将 token 注入到实例中
                    field.set(callLogViewItem, token)
                    tokenToField[token] = field
                }
            }

            val toStingMethodInstance = toStingMethodData.getMethodInstance(classLoader)

            val result = if (toStingMethodData.paramCount == 0) {
                toStingMethodInstance.invoke(callLogViewItem)
            } else {
                toStingMethodInstance.invoke(null, callLogViewItem)
            } as String

            // 分析结果
            val keyword = "subText="
            val index = result.indexOf(keyword)
            if (index != -1) {
                // 截取 geoCodedLocation= 之后的内容
                val after = result.substring(index + keyword.length)

                // 检查内容是以哪个 TOKEN 开头
                for (entry in tokenToField.entries) {
                    if (after.startsWith(entry.key)) {
                        // 找到了 geoCodedLocation 对应字段
                        return entry.value
                    }
                }
            }
            return null
        }

        fun setSubText(bridge: DexKitBridge, subText: Field): MethodData? {
            return bridge.findClass {
                excludePackages(exclusions)
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.FINAL
                    usingStrings("screencall;autopickupreply")
                }
            }.findMethod {
                matcher {
                    paramCount = 2
                    returnType = "void"
                    usingStrings("screencall;autopickupreply")
                    addUsingField {
                        name = subText.name
                        declaredClass(subText.declaringClass)
                    }
                }
            }.singleOrNull()
        }

        System.loadLibrary("dexkit")
        DexKitBridge.create(classLoader, true).use { bridge ->
            val geoCodedLocation = geoCodedLocation(bridge) ?: return null
            val subText = subText(bridge) ?: return null
            val setSubText = setSubText(bridge, subText) ?: return null
            return CallHookConfig(
                versionCode = longVersionCode,
                geoCodedLocationField = DexField(geoCodedLocation).serialize(),
                subTextField = DexField(subText).serialize(),
                setSubTextMethod = setSubText.toDexMethod().serialize(),
            )
        }
    }
}
