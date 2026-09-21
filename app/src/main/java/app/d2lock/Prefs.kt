package app.d2lock

import android.content.Context

object Prefs {
    private const val FILE = "lock_preferences"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("enabled", value).apply()
    fun shizukuMode(context: Context) = prefs(context).getBoolean("shizuku_mode", false)
    fun setShizukuMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean("shizuku_mode", value).apply()
    fun rootMode(context: Context) = prefs(context).getBoolean("root_mode", false)
    fun setRootMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean("root_mode", value).apply()
    fun kiosk(context: Context) = prefs(context).getBoolean("root_kiosk", false)
    fun setKiosk(context: Context, value: Boolean) = prefs(context).edit().putBoolean("root_kiosk", value).apply()
    fun wallpaper(context: Context): String? = prefs(context).getString("wallpaper", null)
    fun setWallpaper(context: Context, value: String) = prefs(context).edit().putString("wallpaper", value).apply()
    fun celsius(context: Context) = prefs(context).getBoolean("celsius", false)
    fun setCelsius(context: Context, value: Boolean) = prefs(context).edit().putBoolean("celsius", value).apply()
    fun showMedia(context: Context) = prefs(context).getBoolean("show_media", true)
    fun showWeatherWidget(context: Context) = prefs(context).getBoolean("widget_weather", true)
    fun setShowWeatherWidget(context: Context, value: Boolean) = prefs(context).edit().putBoolean("widget_weather", value).apply()
    fun showBatteryWidget(context: Context) = prefs(context).getBoolean("widget_battery", true)
    fun setShowBatteryWidget(context: Context, value: Boolean) = prefs(context).edit().putBoolean("widget_battery", value).apply()
    fun detailedBattery(context: Context) = prefs(context).getBoolean("battery_details", true)
    fun setDetailedBattery(context: Context, value: Boolean) = prefs(context).edit().putBoolean("battery_details", value).apply()
    fun oledShift(context: Context) = prefs(context).getBoolean("oled_shift", true)
    fun setOledShift(context: Context, value: Boolean) = prefs(context).edit().putBoolean("oled_shift", value).apply()
    fun clockStyle(context: Context) = prefs(context).getInt("clock_style", 0).coerceIn(0, 2)
    fun setClockStyle(context: Context, value: Int) = prefs(context).edit().putInt("clock_style", value.coerceIn(0, 2)).apply()
    fun setShowMedia(context: Context, value: Boolean) = prefs(context).edit().putBoolean("show_media", value).apply()
    // 0: none, 1: count, 2: app names, 3: public previews.
    fun notificationPrivacy(context: Context) = prefs(context).getInt("notification_privacy", 2)
    fun setNotificationPrivacy(context: Context, value: Int) = prefs(context).edit().putInt("notification_privacy", value).apply()
    fun liveNotifications(context: Context) = prefs(context).getBoolean("live_notifications", true)
    fun setLiveNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean("live_notifications", value).apply()
    fun shortcut(context: Context, side: String) =
        prefs(context).getString("shortcut_$side", if (side == "left") "Camera" else "Flashlight") ?: "None"
    fun setShortcut(context: Context, side: String, value: String) =
        prefs(context).edit().putString("shortcut_$side", value).apply()
}
