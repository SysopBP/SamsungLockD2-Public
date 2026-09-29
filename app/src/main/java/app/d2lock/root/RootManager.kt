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

    private fun root(command: String, timeoutSeconds: Long = 3): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false to "timeout"
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            (process.exitValue() == 0) to output
        }
    }.getOrElse { false to (it.message ?: "error") }

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
        val (ok, output) = root(command, timeoutSeconds)
        return buildString {
            if (output.isNotBlank()) append(output).append("\n")
            append(if (ok) "[exit 0]" else "[command failed]")
        }
    }

    fun isAvailable(): Boolean {
        val (ok, output) = root("id", 2)
        return ok && output.contains("uid=0")
    }

    fun usbAdbState(): UsbAdbState? {
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
    fun enableUsbAdbRecovery(): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val command = """
            settings put global block_usb_lock 0
            settings put global adb_enabled 1
            setprop persist.sys.usb.config adb
            setprop sys.usb.config adb
        """.trimIndent().replace("\n", "; ")
        val (ok, output) = root(command, 5)
        return ok to if (ok) "ADB/USB recovery settings applied" else "Could not apply recovery settings: $output"
    }

    fun disableUsbAdbRecovery(): Pair<Boolean, String> {
        if (!isAvailable()) return false to "Root shell was not detected"
        val (ok, output) = root("settings put global adb_enabled 0; setprop persist.sys.usb.config none; setprop sys.usb.config none", 5)
        return ok to if (ok) "ADB disabled" else "Could not disable ADB: $output"
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
