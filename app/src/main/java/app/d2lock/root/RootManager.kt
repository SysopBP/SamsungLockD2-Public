package app.d2lock.root

import java.util.concurrent.TimeUnit

object RootManager {
    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); false }
        else process.exitValue() == 0 && process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
    }.getOrDefault(false)

    /** Uses root only to request the companion Activity launch; never touches keyguard data. */
    fun launchCompanion(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "am start -n app.d2lock/.lockscreen.LockScreenActivity --activity-single-top")
            .redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); false }
        else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && !output.contains("Error", ignoreCase = true)
        }
    }.getOrDefault(false)
}
