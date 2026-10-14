/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.xlog

object MdecService {
    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun supportCallAndTextOnOtherDevices() {
        if (param.packageName != Package.MDEC_SERVICE) return
        try {
            val clazz = param.classLoader.loadClass("com.samsung.android.mdeccommon.utils.SimUtils")
            val method = clazz.getDeclaredMethod("isChinaSIMActive", Context::class.java)
            xposedModule.hook(method).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { false }
        } catch (t: Throwable) {
            xlog(t)
        }
    }
}
