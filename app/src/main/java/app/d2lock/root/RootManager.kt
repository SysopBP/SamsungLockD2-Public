package app.d2lock.root

import java.util.concurrent.TimeUnit

object RootManager {
    data class UsbAdbState(
        val blockUsbLock: String,
        val adbEnabled: String,
        val usbConfig: String
    ) {
        val summary: String
            get() = "USB lock setting: $blockUsbLock · ADB: $adbEnabled · USB config: $usbConfig"
    }

    private val probeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var cachedRoot = false
    @Volatile private var lastProbe = 0L
    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var cachedFingerprint: FingerprintHardwareInfo? = null
    @Volatile private var cachedUsb: UsbAdbState? = null
    private fun onMain() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    private fun root(command: String, timeoutSeconds: Long = 3): Pair<Boolean, String> = runCatching {
        check(!onMain()) { "Root commands must run off the UI thread" }
        val result = ShellRunner.run(listOf("su", "-c", command), timeoutSeconds)
        result.succeeded() to (result.output.trim() +
            if (result.timedOut) "\nCommand timed out" else if (result.truncated) "\nOutput truncated at 256 KiB" else "")
    }.getOrElse { false to (it.message ?: "error") }

    fun probe(): Boolean {
        check(!onMain())
        val (ok, output) = root("id -u", 3)
        cachedRoot = ok && output == "0"
        lastProbe = android.os.SystemClock.elapsedRealtime()
        return cachedRoot
    }

    fun refreshAsync() {
        if (!probing.compareAndSet(false, true)) return
        probeWorker.execute {
            try {
                if (probe()) {
                    cachedFingerprint = fingerprintHardwareInfo()
                    cachedUsb = usbAdbState()
                } else { cachedFingerprint = null; cachedUsb = null }
            } finally { probing.set(false) }
        }
    }

    data class FingerprintHardwareInfo(
        val productId: String = "Unknown",
        val hardwareSensorId: String = "Unknown",
        val chipSn: String = "Unknown",
        val firmwareVersion: String = "Unknown",
        val maxTemplates: String = "Unknown",
        val frameworkSensorId: String = "Unknown",
        val provider: String = "Unknown",
        val halDeaths: String = "Unknown"
    )

    fun fingerprintHardwareInfo(): FingerprintHardwareInfo? {
        if (onMain()) { refreshAsync(); return cachedFingerprint }
        if (!isAvailable()) return null
        val (ok, dump) = root("dumpsys fingerprint", 5)
        if (!ok || dump.isBlank()) return null

        fun first(vararg patterns: String): String {
            patterns.forEach { pattern ->
                Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                    .find(dump)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return "Not reported"
        }

        val frameworkSensorId = first(
            """Dumping for sensorId:\s*([^,\n]+)""",
            """sensorId\s*[=:]\s*([0-9]+)"""
        )
        val provider = first(
            """Dumping for sensorId:[^\n]*provider:\s*([^\n]+)""",
            """service\s*[=:]\s*["']?([^"',\n]*FingerprintProvider[^"',\n]*)"""
        )
        val maxTemplates = first(
            """Max\s*Template(?:s)?\s*[:=]\s*([^,\n]+)""",
            """maxEnrollmentsPerUser\s*[=:]\s*([0-9]+)"""
        )

        return FingerprintHardwareInfo(
            productId = first("""Product\s*ID\s*[:=]\s*([^,\n]+)""", """productId\s*[=:]\s*([^,\n]+)"""),
            hardwareSensorId = first("""Hardware\s*Sensor\s*ID\s*[:=]\s*([^,\n]+)""", """Sensor\s*ID\s*[:=]\s*([^,\n]+)""", """sensorId\s*[=:]\s*([0-9]+)"""),
            chipSn = first("""Chip\s*S/?N\s*[:=]\s*([^,\n]+)""", """serial(?:Number)?\s*[=:]\s*([^,\n]+)"""),
            firmwareVersion = first("""Firmware(?:\s*Version)?\s*[:=]\s*([^,\n]+)"""),
            maxTemplates = maxTemplates,
            frameworkSensorId = frameworkSensorId,
            provider = provider,
            halDeaths = first("""HAL\s*deaths(?:\s*since\s*last\s*reboot)?\s*[:=]\s*([^\n]+)""")
        )
    }

    /** Interactive Guardian Terminal root backend. */
    fun exec(command: String, timeoutSeconds: Long = 15): String {
        return runCatching {
            check(!onMain()) { "Root commands must run off the UI thread" }
            val result = ShellRunner.run(listOf("su", "-c", command), timeoutSeconds)
            buildString {
                if (result.output.isNotBlank()) append(result.output.trimEnd()).append("\n")
                if (result.truncated) append("[output truncated at 256 KiB]\n")
                if (result.timedOut) append("[command timed out]\n")
                append("[exit ${result.exitCode}]")
            }
        }.getOrElse { "[command failed: ${it.message ?: "error"}]" }
    }

    fun isAvailable(): Boolean {
        if (onMain()) {
            if (android.os.SystemClock.elapsedRealtime() - lastProbe > 30_000L || lastProbe == 0L) refreshAsync()
            return cachedRoot
        }
        return if (android.os.SystemClock.elapsedRealtime() - lastProbe < 5_000L && lastProbe != 0L) cachedRoot else probe()
    }

    fun usbAdbState(): UsbAdbState? {
        if (onMain()) { refreshAsync(); return cachedUsb }
        if (!isAvailable()) return null
        fun read(command: String) = root(command).second.ifBlank { "unknown" }
        return UsbAdbState(
            read("settings get global block_usb_lock"),
            read("settings get global adb_enabled"),
            read("getprop persist.sys.usb.config")
        )
    }

    /**
     * Recovery helper for rooted devices. This does not bypass host authorization:
     * the computer still needs an already-authorized ADB key (or user approval).
     */
    private fun usbSnapshot(context: android.content.Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("d2_usb_recovery_snapshot", android.content.Context.MODE_PRIVATE)
    private fun safeUsbValue(value: String) = value.matches(Regex("[a-zA-Z0-9_,.-]+"))

    fun enableUsbAdbRecovery(context: android.content.Context): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val snapshot = usbSnapshot(context)
        if (!snapshot.getBoolean("saved", false)) {
            val before = usbAdbState() ?: return false to "Could not read USB settings"
            val current = root("getprop sys.usb.config").second
            if (!safeUsbValue(before.usbConfig) || !safeUsbValue(current) ||
                before.adbEnabled !in setOf("0", "1", "null") || before.blockUsbLock !in setOf("0", "1", "null")) {
                return false to "USB state was not readable; recovery settings were not changed"
            }
            if (!snapshot.edit().putString("block", before.blockUsbLock).putString("adb", before.adbEnabled)
                .putString("persist", before.usbConfig).putString("current", current).putBoolean("saved", true).commit()) {
                return false to "Could not save original USB settings"
            }
        }
        val original = snapshot.getString("persist", "none")!!
        val config = (original.split(',').filter { it != "none" && it != "adb" } + "adb").joinToString(",")
        val (ok, output) = root("settings put global block_usb_lock 0 && settings put global adb_enabled 1 && setprop persist.sys.usb.config '$config' && setprop sys.usb.config '$config'", 5)
        val state = usbAdbState()
        cachedUsb = state
        val verified = ok && state?.blockUsbLock == "0" && state.adbEnabled == "1" && state.usbConfig == config
        if (!verified) {
            val restored = disableUsbAdbRecovery(context)
            return false to "USB recovery could not be verified: $output. ${restored.second}"
        }
        return true to "ADB/USB recovery verified; original USB functions preserved"
    }

    fun disableUsbAdbRecovery(context: android.content.Context): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val snapshot = usbSnapshot(context)
        if (!snapshot.getBoolean("saved", false)) {
            return false to "No original USB snapshot exists. Restore USB settings in Android settings."
        }
        val block = snapshot.getString("block", "null")!!
        val adb = snapshot.getString("adb", "0")!!
        val persist = snapshot.getString("persist", "none")!!
        val current = snapshot.getString("current", "none")!!
        if (!safeUsbValue(persist) || !safeUsbValue(current) || block !in setOf("0", "1", "null") || adb !in setOf("0", "1", "null")) {
            return false to "Original USB snapshot is invalid; no settings changed"
        }
        fun restoreSetting(key: String, value: String) = if (value == "null") "settings delete global $key" else "settings put global $key $value"
        val (ok, output) = root(restoreSetting("block_usb_lock", block) + " && " + restoreSetting("adb_enabled", adb) +
            " && setprop persist.sys.usb.config '$persist' && setprop sys.usb.config '$current'", 5)
        val state = usbAdbState()
        cachedUsb = state
        val verified = ok && state?.blockUsbLock == block && state.adbEnabled == adb && state.usbConfig == persist
        if (verified) snapshot.edit().clear().commit()
        return verified to if (verified) "Original USB settings restored and verified" else "Could not verify USB restoration: $output"
    }

    /** Restart SystemUI without rebooting Android. Intended for root/LSPosed integration testing. */
    fun restartSystemUi(): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val (ok, output) = root("pkill -f com.android.systemui", 5)
        return ok to if (ok) "System UI restart requested" else "Could not restart System UI: $output"
    }

    /** Request Android's framework soft reboot. This does not perform a hardware reboot. */
    fun softReboot(): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val (ok, output) = root("setprop ctl.restart zygote", 5)
        return ok to if (ok) "Soft reboot requested" else "Could not request soft reboot: $output"
    }

    /** Uses root only to request the companion Activity launch; never touches keyguard data. */
    fun launchCompanion(): Boolean {
        val (ok, output) = root("am start -n app.d2lock/.lockscreen.LockScreenActivity --activity-single-top")
        return ok && !output.contains("Error", ignoreCase = true)
    }
}
