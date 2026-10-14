/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ b8f81448b2bcaa314c0cb8abec0c8ff527a14e27. */
package io.github.soclear.oneuix.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.xlog

object Calendar {
    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun enableChineseHolidayDisplay() {
        if (param.packageName != Package.CALENDAR) return

        try {
            val semCscFeatureClass =
                param.classLoader.loadClass("com.samsung.android.feature.SemCscFeature")

            xposedModule.hook(
                semCscFeatureClass.getDeclaredMethod("getString", String::class.java, String::class.java)
            ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                if (chain.args[0] == "CscFeature_Calendar_EnableLocalHolidayDisplay") {
                    "CHINA"
                } else {
                    result
                }
            }

            xposedModule.hook(
                semCscFeatureClass.getDeclaredMethod("getString", String::class.java)
            ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                if (chain.args[0] == "CscFeature_Calendar_EnableLocalHolidayDisplay") {
                    "CHINA"
                } else {
                    result
                }
            }
        } catch (t: Throwable) {
            xlog(t)
        }
    }
}
