package app.d2lock.root

import java.util.concurrent.TimeUnit

object AdbManager {
    data class Status(
        val enabled: Boolean,
        val connected: Boolean,
        val configured: Boolean,
        val state: String
    )

    private fun root(command: String, timeoutSeconds: Long = 4): Pair<Int, String>? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.exitValue() to process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()

    fun status(): Status {
        val enabled = root("settings get global adb_enabled")?.second == "1"
        val state = root("getprop sys.usb.state")?.second.orEmpty()
        val configured = root("getprop sys.usb.configured")?.second.orEmpty() != "none"
        val connected = root("dumpsys usb | grep -m1 'kernel_state=' | grep -qv DISCONNECTED")?.first == 0
        return Status(enabled, connected, configured, state)
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = root("settings put global adb_enabled $value; setprop persist.sys.usb.config \"\$(echo \$(getprop persist.sys.usb.config) | sed 's/,adb//g;s/adb,//g;s/^adb$//')\"; " +
            if (enabled) "setprop persist.sys.usb.config \"\$(getprop persist.sys.usb.config),adb\"; setprop ctl.restart adbd"
            else "setprop ctl.stop adbd")
        return result?.first == 0
    }
}
