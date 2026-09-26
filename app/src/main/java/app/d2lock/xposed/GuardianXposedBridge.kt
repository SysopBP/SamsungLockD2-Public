package app.d2lock.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Guardian Xposed Bridge v0.1.
 *
 * Intentionally read-mostly: verifies that modern LSPosed can load Guardian in SystemUI
 * and exposes lifecycle breadcrumbs before we add any Samsung/SystemUI hooks.
 */
class GuardianXposedBridge : XposedModule() {
    companion object {
        private const val TAG = "D2XposedBridge"
        private const val SYSTEM_UI = "com.android.systemui"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_XPOSED_LOADED process=${param.processName} api=$apiVersion framework=$frameworkName"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_XPOSED_SYSTEMUI_READY package=${param.packageName} process=${param.processName}"
        )
    }
}
