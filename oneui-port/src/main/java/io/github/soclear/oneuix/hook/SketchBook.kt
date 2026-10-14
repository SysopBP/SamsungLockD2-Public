/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ b8f81448b2bcaa314c0cb8abec0c8ff527a14e27. */
package io.github.soclear.oneuix.hook

import android.content.Context
import android.graphics.Bitmap
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.xlog

object SketchBook {
    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun noAIWatermark() {
        if (param.packageName != Package.SKETCH_BOOK) return
        try {
            // 去掉实际保存的水印
            xposedModule.hook(
                param.classLoader.loadClass("com.samsung.android.app.sketchbook.common.utils.watermark.WatermarkUtils")
                    .getDeclaredMethod(
                        "combineWatermark",
                        Context::class.java,
                        Bitmap::class.java,
                        Bitmap::class.java
                    )
            ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                // originBitmap
                chain.args[1]
            }
        } catch (t: Throwable) {
            xlog(t)
        }
        try {
            // 去掉预览水印
            val watermarkOverlayUtilsClass = param.classLoader.loadClass(
                "com.samsung.android.app.sketchbook.common.utils.watermark.WatermarkOverlayUtils"
            )
            watermarkOverlayUtilsClass.declaredMethods.filter {
                it.name == "setupWatermarkOverlay"
            }.forEach {
                xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { null }
            }
        } catch (t: Throwable) {
            xlog(t)
        }
    }
}
