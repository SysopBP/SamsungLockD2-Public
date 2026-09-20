package app.d2lock.root

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.telecom.InCallService
import android.telecom.TelecomManager

/** Resolve phone UI packages, never arbitrary apps posting a call-shaped notification. */
object KioskCallApps {
    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val result = linkedSetOf<String>()
        fun addInstalled(name: String?) {
            if (name.isNullOrBlank()) return
            if (runCatching { pm.getApplicationInfo(name, 0).enabled }.getOrDefault(false)) result.add(name)
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        addInstalled(runCatching { telecom?.defaultDialerPackage }.getOrNull())
        addInstalled(runCatching { telecom?.systemDialerPackage }.getOrNull())
        // Samsung may keep its call UI separate from the dialer's launcher package.
        pm.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .forEach { entry ->
                val service = entry.serviceInfo ?: return@forEach
                val app = service.applicationInfo
                val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (system && service.enabled && app.enabled &&
                    service.permission == Manifest.permission.BIND_INCALL_SERVICE &&
                    service.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI) == true) {
                    result.add(service.packageName)
                }
            }
        // Some Samsung firmware omits the standard UI metadata from this system package.
        val samsung = "com.samsung.android.incallui"
        runCatching { pm.getApplicationInfo(samsung, 0) }.getOrNull()?.let {
            if (it.enabled && it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                result.add(samsung)
        }
        return result
    }
}
