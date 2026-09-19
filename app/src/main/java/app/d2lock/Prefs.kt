package app.d2lock

import android.content.Context

object Prefs {
    private const val FILE = "lock_preferences"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("enabled", value).apply()
    fun rootMode(context: Context) = prefs(context).getBoolean("root_mode", false)
    fun setRootMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean("root_mode", value).apply()
    fun kiosk(context: Context) = prefs(context).getBoolean("root_kiosk", false)
    fun setKiosk(context: Context, value: Boolean) = prefs(context).edit().putBoolean("root_kiosk", value).apply()
    fun wallpaper(context: Context): String? = prefs(context).getString("wallpaper", null)
    fun setWallpaper(context: Context, value: String) = prefs(context).edit().putString("wallpaper", value).apply()
}
