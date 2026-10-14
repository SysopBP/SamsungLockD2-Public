package app.d2lock.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Optional screen-off experiment. No screen contents, event monitoring or unlock actions. */
class D2AccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        Log.i(TAG, "ACCESSIBILITY_CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "D2AccessibilityLab"
        @Volatile private var connected: D2AccessibilityService? = null
        private fun prefs(context: Context) = context.getSharedPreferences("d2_accessibility_lab", Context.MODE_PRIVATE)
        fun isConnected() = connected != null
        fun testRouteEnabled(context: Context) = prefs(context).getBoolean("double_tap_test", false)
        fun setTestRoute(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean("double_tap_test", enabled).apply()
        }
        fun status(context: Context): String =
            (if (isConnected()) "Connected" else "Not connected") +
                " · Double-tap test " + if (testRouteEnabled(context)) "ON" else "OFF"

        /** A true result means Android accepted the action, not a verified screen-off outcome. */
        fun requestScreenOff(context: Context, manualTest: Boolean = false): Boolean {
            if (!manualTest && !testRouteEnabled(context)) return false
            val service = connected
            val accepted = service != null && runCatching {
                service.systemActions.any { it.id == GLOBAL_ACTION_LOCK_SCREEN } &&
                    service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }.getOrDefault(false)
            prefs(context).edit().putLong("request_time", System.currentTimeMillis())
                .putString("request_result", if (accepted) "Android accepted screen-lock action" else "Action unavailable or rejected")
                .apply()
            Log.i(TAG, "ACCESSIBILITY_SCREEN_OFF accepted=$accepted manual=$manualTest")
            return accepted
        }
        fun lastRequest(context: Context): String {
            val stored = prefs(context)
            val time = stored.getLong("request_time", 0L)
            if (time == 0L) return "No screen-off test requested"
            return stored.getString("request_result", "Unknown") + " · " +
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
        }
        fun disable(context: Context) {
            setTestRoute(context, false)
            connected?.disableSelf()
        }
    }
}
