/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook.systemui

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog

object AOD {
    @SuppressLint("PrivateApi")
    context(xposedModule: XposedModule)
    fun hideAODStatusBar() = afterAttachTry(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        classLoader.loadClass("com.android.systemui.battery.BatteryMeterViewController")
            .getDeclaredMethod("onViewAttached")
            .let { xposedModule.hook(it) }
            .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    val batteryView = chain.thisObject.reflect["mView"] as View
                    if (batteryView.tag == "PluginFaceWidgetManager") {
                        val parentView = batteryView.parent.parent as View
                        parentView.visibility = View.GONE
                    }
                } catch (t: Throwable) {
                    xlog(t)
                }
                result
            }
    }

    context(xposedModule: XposedModule)
    fun aodLockSupportLunar() = afterAttachTry {
        val semCscFeatureClass = classLoader.loadClass("com.samsung.android.feature.SemCscFeature")

        val hooker = XposedInterface.Hooker { chain ->
            if (chain.args[0] == "CscFeature_Calendar_EnableLocalHolidayDisplay") {
                "CHINA"
            } else {
                chain.proceed()
            }
        }

        listOf(
            semCscFeatureClass.getDeclaredMethod("getString", String::class.java),
            semCscFeatureClass.getDeclaredMethod("getString", String::class.java, String::class.java)
        ).forEach { method ->
            xposedModule.hook(method).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
        }
    }
}
