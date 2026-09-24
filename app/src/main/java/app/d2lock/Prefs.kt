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
    fun adbRecovery(context: Context) = prefs(context).getBoolean("adb_recovery", false)
    fun setAdbRecovery(context: Context, value: Boolean) = prefs(context).edit().putBoolean("adb_recovery", value).apply()
    fun quickSettingsGuard(context: Context) = prefs(context).getBoolean("quick_settings_guard", false)
    fun setQuickSettingsGuard(context: Context, value: Boolean) = prefs(context).edit().putBoolean("quick_settings_guard", value).apply()
    fun shizukuEnabled(context: Context) = prefs(context).getBoolean("shizuku_enabled", true)
    fun setShizukuEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("shizuku_enabled", value).apply()
    fun unlockMethod(context: Context) = prefs(context).getString("unlock_method", "pin") ?: "pin"
    fun floatingUnlockGesture(context: Context) = prefs(context).getString("floating_unlock_gesture", "tap_or_slide") ?: "tap_or_slide"
    fun setFloatingUnlockGesture(context: Context, value: String) = prefs(context).edit().putString("floating_unlock_gesture", value).apply()
    fun setUnlockMethod(context: Context, value: String) = prefs(context).edit().putString("unlock_method", value).apply()
    fun wallpaper(context: Context): String? = prefs(context).getString("wallpaper", null)
    fun setWallpaper(context: Context, value: String) = prefs(context).edit().putString("wallpaper", value).apply()
    fun wallpaperDim(context: Context) = prefs(context).getInt("wallpaper_dim", 30).coerceIn(0, 80)
    fun wallpaperParallax(context: Context) = prefs(context).getBoolean("wallpaper_parallax", false)
    fun setWallpaperParallax(context: Context, value: Boolean) = prefs(context).edit().putBoolean("wallpaper_parallax", value).apply()
    fun glassShimmer(context: Context) = prefs(context).getBoolean("glass_shimmer", false)
    fun setGlassShimmer(context: Context, value: Boolean) = prefs(context).edit().putBoolean("glass_shimmer", value).apply()
    fun doubleTapSleep(context: Context) = prefs(context).getBoolean("double_tap_sleep", false)
    fun setDoubleTapSleep(context: Context, value: Boolean) = prefs(context).edit().putBoolean("double_tap_sleep", value).apply()
    fun unlockHaptics(context: Context) = prefs(context).getString("unlock_haptics", "off") ?: "off"
    fun setUnlockHaptics(context: Context, value: String) = prefs(context).edit().putString("unlock_haptics", value).apply()
    fun setWallpaperDim(context: Context, value: Int) = prefs(context).edit().putInt("wallpaper_dim", value.coerceIn(0, 80)).apply()
    fun topInfoSize(context: Context) = prefs(context).getInt("top_info_size", 100).coerceIn(80, 120)
    fun setTopInfoSize(context: Context, value: Int) = prefs(context).edit().putInt("top_info_size", value.coerceIn(80, 120)).apply()
    fun showWeather(context: Context) = prefs(context).getBoolean("show_weather", true)
    fun setShowWeather(context: Context, value: Boolean) = prefs(context).edit().putBoolean("show_weather", value).apply()
    fun showBatteryPercent(context: Context) = prefs(context).getBoolean("show_battery_percent", true)
    fun setShowBatteryPercent(context: Context, value: Boolean) = prefs(context).edit().putBoolean("show_battery_percent", value).apply()
    fun resetAppearance(context: Context) {
        prefs(context).edit()
            .remove("wallpaper_dim").remove("lock_glass").remove("lock_scale")
            .remove("clock_style").remove("clock_layout").remove("clock_adaptive").remove("clock_scale").remove("show_date")
            .remove("glass_top_info").remove("glass_media").remove("top_info_size").remove("show_weather").remove("show_battery_percent")
            .remove("media_layout").remove("media_buttons_scale")
            .remove("notification_density").remove("notification_radius").remove("notification_glass")
            .apply()
    }
    fun celsius(context: Context) = prefs(context).getBoolean("celsius", false)
    fun setCelsius(context: Context, value: Boolean) = prefs(context).edit().putBoolean("celsius", value).apply()
    fun weatherLocation(context: Context) = prefs(context).getString("weather_location", "") ?: ""
    fun setWeatherLocation(context: Context, value: String) = prefs(context).edit().putString("weather_location", value.trim()).apply()
    fun lockGlass(context: Context) = prefs(context).getInt("lock_glass", 70).coerceIn(20, 100)
    fun setLockGlass(context: Context, value: Int) = prefs(context).edit().putInt("lock_glass", value.coerceIn(20, 100)).apply()
    fun lockScale(context: Context) = prefs(context).getInt("lock_scale", 100).coerceIn(85, 115)
    fun setLockScale(context: Context, value: Int) = prefs(context).edit().putInt("lock_scale", value.coerceIn(85, 115)).apply()
    fun clockStyle(context: Context) = prefs(context).getString("clock_style", "adaptive") ?: "adaptive"
    fun setClockStyle(context: Context, value: String) = prefs(context).edit().putString("clock_style", value).apply()
    fun clockLayout(context: Context) = prefs(context).getString("clock_layout", "auto") ?: "auto"
    fun setClockLayout(context: Context, value: String) = prefs(context).edit().putString("clock_layout", value).apply()
    fun clockAdaptive(context: Context) = prefs(context).getBoolean("clock_adaptive", true)
    fun setClockAdaptive(context: Context, value: Boolean) = prefs(context).edit().putBoolean("clock_adaptive", value).apply()
    fun clockScale(context: Context) = prefs(context).getInt("clock_scale", 100).coerceIn(80, 130)
    fun setClockScale(context: Context, value: Int) = prefs(context).edit().putInt("clock_scale", value.coerceIn(80, 130)).apply()
    fun showDate(context: Context) = prefs(context).getBoolean("show_date", true)
    fun setShowDate(context: Context, value: Boolean) = prefs(context).edit().putBoolean("show_date", value).apply()
    fun componentGlass(context: Context, component: String) =
        prefs(context).getInt("glass_$component", lockGlass(context)).coerceIn(20, 100)
    fun setComponentGlass(context: Context, component: String, value: Int) =
        prefs(context).edit().putInt("glass_$component", value.coerceIn(20, 100)).apply()
    fun mediaLayout(context: Context) = prefs(context).getString("media_layout", "comfortable") ?: "comfortable"
    fun setMediaLayout(context: Context, value: String) = prefs(context).edit().putString("media_layout", value).apply()
    fun mediaButtonsScale(context: Context) = prefs(context).getInt("media_buttons_scale", 100).coerceIn(80, 120)
    fun setMediaButtonsScale(context: Context, value: Int) = prefs(context).edit().putInt("media_buttons_scale", value.coerceIn(80, 120)).apply()
    fun showMedia(context: Context) = prefs(context).getBoolean("show_media", true)
    fun setShowMedia(context: Context, value: Boolean) = prefs(context).edit().putBoolean("show_media", value).apply()
    fun notificationDensity(context: Context) = prefs(context).getString("notification_density", "comfortable") ?: "comfortable"
    fun setNotificationDensity(context: Context, value: String) = prefs(context).edit().putString("notification_density", value).apply()
    fun notificationRadius(context: Context) = prefs(context).getInt("notification_radius", 28).coerceIn(16, 40)
    fun setNotificationRadius(context: Context, value: Int) = prefs(context).edit().putInt("notification_radius", value.coerceIn(16, 40)).apply()
    fun notificationGlass(context: Context) = prefs(context).getInt("notification_glass", lockGlass(context)).coerceIn(20, 100)
    fun setNotificationGlass(context: Context, value: Int) = prefs(context).edit().putInt("notification_glass", value.coerceIn(20, 100)).apply()
    fun notificationPrivacy(context: Context) = prefs(context).getInt("notification_privacy", 2)
    fun setNotificationPrivacy(context: Context, value: Int) = prefs(context).edit().putInt("notification_privacy", value).apply()
    fun liveNotifications(context: Context) = prefs(context).getBoolean("live_notifications", true)
    fun setLiveNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean("live_notifications", value).apply()
    fun shortcut(context: Context, side: String) =
        prefs(context).getString("shortcut_$side", if (side == "left") "Camera" else "Flashlight") ?: "None"
    fun setShortcut(context: Context, side: String, value: String) =
        prefs(context).edit().putString("shortcut_$side", value).apply()
}
