/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.afterAttach
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog

@SuppressLint("PrivateApi")
object CoreRune {
    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun supportAppJumpBlockAndroid() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        try {
            xposedModule.hook(
                param.classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
                    .getDeclaredConstructor(Context::class.java)
            ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                try {
                    param.classLoader
                        .loadClass("com.samsung.android.rune.CoreRune").reflect["SUPPORT_APP_JUMP_BLOCK"] = true
                } catch (t: Throwable) {
                    xlog(t)
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    context(xposedModule: XposedModule, param: XposedModuleInterface.PackageReadyParam)
    fun supportAppJumpBlockSettings() {
        if (param.packageName != Package.SETTINGS ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            return
        }
        val infix =
            if (param.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.BAKLAVA) {
                "security"
            } else {
                "privacy"
            }
        afterAttach {
            try {
                xposedModule.hook(
                    param.classLoader
                        .loadClass("com.samsung.android.settings.$infix.AppRedirectInterceptionPreferenceController")
                        .getDeclaredMethod("getAvailabilityStatus")
                ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    try {
                        param.classLoader
                            .loadClass("com.samsung.android.rune.CoreRune").reflect["SUPPORT_APP_JUMP_BLOCK"] = true
                    } catch (t: Throwable) {
                        xlog(t)
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                xlog(t)
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    context(xposedModule: XposedModule, param: XposedModuleInterface.SystemServerStartingParam)
    fun allowAllRotation() {
        try {
            val coreRuneClass = param.classLoader.loadClass("com.samsung.android.rune.CoreRune")
            coreRuneClass.reflect["FW_ALLOW_ALL_ROTATION"] = true
            coreRuneClass.reflect["FW_ORIENTATION_CONTROL"] = true
            xposedModule.hook(
                param.classLoader.loadClass("com.android.internal.view.RotationPolicy")
                    .getDeclaredMethod("areAllRotationsAllowed", Context::class.java)
            ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { true }
        } catch (t: Throwable) {
            xlog(t)
        }
    }
}
