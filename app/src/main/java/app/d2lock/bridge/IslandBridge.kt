package app.d2lock.bridge

import android.content.Context
import android.net.Uri

/** Private, persistent privacy state; only a verified PIN clears an active lock. */
object IslandBridge {
    val uri: Uri = Uri.parse("content://app.d2lock.island")
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("island_bridge", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun locked(context: Context): Boolean = prefs(context).getBoolean("locked", false)
    fun setEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("enabled", value).commit())
        context.contentResolver.notifyChange(uri, null)
    }
    fun setLocked(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("locked", value).commit())
        context.contentResolver.notifyChange(uri, null)
    }
}
