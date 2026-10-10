package app.d2lock.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** Elapsed time prevents clock edits from bypassing an active delay in this boot. */
internal class AttemptThrottle(context: Context, private val testClock: (() -> Long)? = null) {
    private val resolver = context.contentResolver
    private fun wall() = testClock?.invoke() ?: System.currentTimeMillis()
    private fun boot() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
    fun record(state: JSONObject, delay: Long) {
        state.put("until", wall() + delay).put("delay", delay)
        if (testClock == null) state.put("until_elapsed", SystemClock.elapsedRealtime() + delay).put("boot", boot())
    }
    fun remaining(state: JSONObject): Long {
        val sameBoot = testClock == null && boot() >= 0 && state.optInt("boot", -2) == boot() && state.has("until_elapsed")
        val remaining = if (sameBoot) state.optLong("until_elapsed") - SystemClock.elapsedRealtime() else state.optLong("until") - wall()
        return remaining.coerceIn(0L, state.optLong("delay", 960000L).coerceIn(0L, 960000L))
    }
    fun reset(state: JSONObject) { state.put("until", 0L).put("until_elapsed", 0L).put("delay", 0L) }
}
