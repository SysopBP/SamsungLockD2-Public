package app.d2lock

import android.content.Context
import android.net.Uri

object Prefs {
    private const val FILE = "lock_preferences"
    private val checkedStores = mutableSetOf<Boolean>()
    /**
     * Direct-Boot safe preference access.
     *
     * BOOT_READY can start the guardian before credential-encrypted app storage is
     * available. Use device-protected storage for that short window so the lock
     * surface can render with safe defaults instead of crashing. As soon as user 0
     * is unlocked, reads/writes return to the existing credential-protected store.
     */
    private fun prefs(context: Context): android.content.SharedPreferences {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        val storageContext = if (userManager?.isUserUnlocked != false) {
            context
        } else {
            context.createDeviceProtectedStorageContext()
        }
        val preferences = storageContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        synchronized(checkedStores) {
            if (checkedStores.add(storageContext.isDeviceProtectedStorage)) {
                val bad = preferences.all.filter { (key, value) -> !SettingsSchema.validStoredType(key, value) }.keys
                if (bad.isNotEmpty()) {
                    val edit = preferences.edit()
                    bad.forEach { edit.remove(it) }
                    edit.putBoolean("enabled", false).commit()
                    android.util.Log.w("SamsungLockD2", "Invalid settings removed; Guardian disabled until reviewed: $bad")
                }
            }
        }
        return preferences
    }

    fun syncBootPolicy(context: Context) {
        if (context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false) return
        context.createDeviceProtectedStorageContext().getSharedPreferences("guardian_boot_policy", Context.MODE_PRIVATE)
            .edit().putInt("version", 1).putBoolean("enabled", enabled(context))
            .putBoolean("root", rootMode(context)).putBoolean("master", xposedMaster(context))
            .putBoolean("credential_ready", app.d2lock.security.PinStore(context).configured()).commit()
    }
    fun earlyBootArmed(context: Context): Boolean {
        val p = context.createDeviceProtectedStorageContext().getSharedPreferences("guardian_boot_policy", Context.MODE_PRIVATE)
        return p.getInt("version", 0) == 1 && p.getBoolean("enabled", false) &&
            p.getBoolean("root", false) && p.getBoolean("master", false) && p.getBoolean("credential_ready", false)
    }

    fun exportSettings(context: Context): String {
        val json = org.json.JSONObject()
        prefs(context).all.forEach { (key, value) ->
            if (key !in SettingsSchema.protectedKeys && value != null) json.put(key, value)
        }
        // Validate exports too: previously corrupt preferences must not spread to another install.
        SettingsSchema.validate(json)
        val appearance = org.json.JSONObject(context.getSharedPreferences("d2_appearance", Context.MODE_PRIVATE).all)
        return org.json.JSONObject().put("schema", 2).put("lock", json)
            .put("appearance", appearance).put("icon", IconManager.selected(context)).toString(2)
    }

    fun importSettings(context: Context, raw: String): Int {
        require(raw.length <= 524288) { "Backup is too large" }
        val document = org.json.JSONObject(raw)
        val modern = document.has("schema")
        val values = SettingsSchema.validateDocument(document)
        val appearance = if (modern) document.getJSONObject("appearance") else org.json.JSONObject()
        val icon = if (modern) document.getString("icon") else null
        val edit = prefs(context).edit()
        var count = 0
        for ((key, value) in values) {
            if (key in setOf("wallpaper", "app_wallpaper") && value is String && value.startsWith("content://")) {
                val readable = runCatching { context.contentResolver.openInputStream(Uri.parse(value))?.use { true } ?: false }.getOrDefault(false)
                if (!readable) continue
            }
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
            }
            count++
        }
        check(edit.commit()) { "Could not save restored settings" }
        if (modern) {
            val e = context.getSharedPreferences("d2_appearance", Context.MODE_PRIVATE).edit()
            for (key in appearance.keys()) e.putInt(key, (appearance.get(key) as Number).toInt())
            check(e.commit()) { "Could not restore appearance" }
            IconManager.apply(context, icon!!)
        }
        return count
    }

    fun applyProfile(context: Context, profile: String) {
        val e = prefs(context).edit()
        when (profile) {
            "daily" -> e.putInt("wallpaper_dim", 24).putInt("lock_glass", 72)
                .putString("clock_style", "adaptive").putBoolean("clock_adaptive", true)
                .putInt("clock_scale", 100).putBoolean("show_date", true)
                .putBoolean("show_weather", true).putBoolean("show_battery_percent", true)
                .putString("notification_density", "comfortable").putInt("notification_radius", 28)
                .putInt("notification_glass", 72).putBoolean("show_media", true)
                .putString("media_layout", "comfortable").putString("floating_unlock_gesture", "tap_or_slide")
            "amoled" -> e.putInt("wallpaper_dim", 68).putInt("lock_glass", 88)
                .putString("clock_style", "adaptive").putBoolean("clock_adaptive", true)
                .putInt("clock_scale", 100).putBoolean("show_date", true)
                .putBoolean("show_weather", true).putBoolean("show_battery_percent", true)
                .putString("notification_density", "compact").putInt("notification_radius", 26)
                .putInt("notification_glass", 88).putBoolean("show_media", true)
                .putString("media_layout", "compact").putString("floating_unlock_gesture", "tap_or_slide")
            "minimal" -> e.putInt("wallpaper_dim", 38).putInt("lock_glass", 82)
                .putString("clock_style", "adaptive").putBoolean("clock_adaptive", true)
                .putInt("clock_scale", 92).putBoolean("show_date", true)
                .putBoolean("show_weather", false).putBoolean("show_battery_percent", false)
                .putString("notification_density", "compact").putInt("notification_radius", 30)
                .putInt("notification_glass", 84).putBoolean("show_media", false)
                .putString("floating_unlock_gesture", "slide_only")
            "night" -> e.putInt("wallpaper_dim", 58).putInt("lock_glass", 90)
                .putString("clock_style", "adaptive").putBoolean("clock_adaptive", true)
                .putInt("clock_scale", 96).putBoolean("show_date", true)
                .putBoolean("show_weather", true).putBoolean("show_battery_percent", true)
                .putString("notification_density", "compact").putInt("notification_radius", 32)
                .putInt("notification_glass", 90).putBoolean("show_media", true)
                .putString("media_layout", "compact").putString("floating_unlock_gesture", "slide_only")
            else -> return
        }
        e.putString("active_profile", profile).apply()
    }

    fun activeProfile(context: Context) = prefs(context).getString("active_profile", "custom") ?: "custom"

    private val profileExcluded = SettingsSchema.protectedKeys

    fun customProfileNames(context: Context): List<String> {
        val raw = prefs(context).getString("custom_profiles", "{}") ?: "{}"
        val json = runCatching { org.json.JSONObject(raw) }.getOrDefault(org.json.JSONObject())
        return buildList {
            val keys = json.keys()
            while (keys.hasNext()) add(keys.next())
        }.sorted()
    }

    fun saveCustomProfile(context: Context, name: String) {
        val clean = name.trim().take(32)
        require(clean.isNotBlank())
        val store = runCatching { org.json.JSONObject(prefs(context).getString("custom_profiles", "{}") ?: "{}") }
            .getOrDefault(org.json.JSONObject())
        require(store.has(clean) || store.length() < 32) { "At most 32 custom profiles can be saved" }
        val snapshot = org.json.JSONObject(exportSettings(context))
        snapshot.getJSONObject("lock").remove("custom_profiles")
        store.put(clean, snapshot)
        prefs(context).edit().putString("custom_profiles", store.toString()).putString("active_profile", "custom:$clean").apply()
    }

    fun applyCustomProfile(context: Context, name: String): Boolean {
        val store = runCatching { org.json.JSONObject(prefs(context).getString("custom_profiles", "{}") ?: "{}") }.getOrNull() ?: return false
        val snapshot = store.optJSONObject(name) ?: return false
        val restored = runCatching { importSettings(context, snapshot.toString()) }.isSuccess
        if (!restored) return false
        prefs(context).edit().putString("active_profile", "custom:$name").apply()
        return true
    }

    fun deleteCustomProfile(context: Context, name: String) {
        val store = runCatching { org.json.JSONObject(prefs(context).getString("custom_profiles", "{}") ?: "{}") }.getOrNull() ?: return
        store.remove(name)
        val e = prefs(context).edit().putString("custom_profiles", store.toString())
        if (activeProfile(context) == "custom:$name") e.putString("active_profile", "custom")
        e.apply()
    }

    // Xposed integration switches. Behavioral hooks default OFF so they can be validated one by one.
    fun xposedMaster(context: Context) = prefs(context).getBoolean("xposed_master", true)
    fun setXposedMaster(context: Context, value: Boolean) { prefs(context).edit().putBoolean("xposed_master", value).apply(); syncBootPolicy(context) }
    fun xposedSafeDiagnostics(context: Context) = prefs(context).getBoolean("xposed_safe_diagnostics", true)
    fun setXposedSafeDiagnostics(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_safe_diagnostics", value).apply()
    fun xposedImmediateRelock(context: Context) = prefs(context).getBoolean("xposed_immediate_relock", false)
    fun setXposedImmediateRelock(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_immediate_relock", value).apply()
    fun xposedScreenAwareness(context: Context) = prefs(context).getBoolean("xposed_screen_awareness", false)
    fun setXposedScreenAwareness(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_screen_awareness", value).apply()
    fun xposedHomeRecents(context: Context) = prefs(context).getBoolean("xposed_home_recents", false)
    fun setXposedHomeRecents(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_home_recents", value).apply()
    fun xposedGestureCoordination(context: Context) = prefs(context).getBoolean("xposed_gesture_coordination", false)
    fun setXposedGestureCoordination(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_gesture_coordination", value).apply()
    fun xposedNotifications(context: Context) = prefs(context).getBoolean("xposed_notifications", false)
    fun setXposedNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_notifications", value).apply()
    fun xposedMedia(context: Context) = prefs(context).getBoolean("xposed_media", false)
    fun setXposedMedia(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_media", value).apply()
    fun xposedCalls(context: Context) = prefs(context).getBoolean("xposed_calls", false)
    fun setXposedCalls(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_calls", value).apply()
    fun xposedSystemUiRecovery(context: Context) = prefs(context).getBoolean("xposed_systemui_recovery", false)
    fun setXposedSystemUiRecovery(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_systemui_recovery", value).apply()
    fun xposedBarsGuard(context: Context) = prefs(context).getBoolean("xposed_bars_guard", false)
    fun setXposedBarsGuard(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_bars_guard", value).apply()
    fun xposedNativeLifecycle(context: Context) = prefs(context).getBoolean("xposed_native_lifecycle", false)
    fun setXposedNativeLifecycle(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_native_lifecycle", value).apply()
    fun xposedGalaxyIsland(context: Context) = prefs(context).getBoolean("xposed_galaxy_island", false)
    fun setXposedGalaxyIsland(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_galaxy_island", value).apply()
    fun xposedIslandNotifications(context: Context) = prefs(context).getBoolean("xposed_island_notifications", false)
    fun setXposedIslandNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_notifications", value).apply()
    fun xposedIslandMedia(context: Context) = prefs(context).getBoolean("xposed_island_media", false)
    fun setXposedIslandMedia(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_media", value).apply()
    fun xposedIslandCharging(context: Context) = prefs(context).getBoolean("xposed_island_charging", false)
    fun setXposedIslandCharging(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_charging", value).apply()
    fun xposedIslandCalls(context: Context) = prefs(context).getBoolean("xposed_island_calls", false)
    fun setXposedIslandCalls(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_calls", value).apply()
    fun xposedIslandScreenState(context: Context) = prefs(context).getBoolean("xposed_island_screen_state", false)
    fun setXposedIslandScreenState(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_screen_state", value).apply()
    fun xposedIslandPositioning(context: Context) = prefs(context).getBoolean("xposed_island_positioning", false)
    fun setXposedIslandPositioning(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_positioning", value).apply()
    fun xposedIslandGuardianSync(context: Context) = prefs(context).getBoolean("xposed_island_guardian_sync", false)
    fun setXposedIslandGuardianSync(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_guardian_sync", value).apply()
    fun xposedIslandFallback(context: Context) = prefs(context).getBoolean("xposed_island_fallback", true)
    fun setXposedIslandFallback(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_island_fallback", value).apply()

    fun xposedAutomaticFallback(context: Context) = prefs(context).getBoolean("xposed_automatic_fallback", true)
    fun setXposedAutomaticFallback(context: Context, value: Boolean) = prefs(context).edit().putBoolean("xposed_automatic_fallback", value).apply()

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply(); syncBootPolicy(context) }
    fun rootMode(context: Context) = prefs(context).getBoolean("root_mode", false)
    fun setRootMode(context: Context, value: Boolean) { prefs(context).edit().putBoolean("root_mode", value).apply(); syncBootPolicy(context) }
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
    fun appWallpaper(context: Context): String? = prefs(context).getString("app_wallpaper", null)
    fun setAppWallpaper(context: Context, value: String) = prefs(context).edit().putString("app_wallpaper", value).apply()
    fun clearAppWallpaper(context: Context) = prefs(context).edit().remove("app_wallpaper").apply()
    fun appWallpaperDim(context: Context) = prefs(context).getInt("app_wallpaper_dim", 48).coerceIn(0, 90)
    fun setAppWallpaperDim(context: Context, value: Int) = prefs(context).edit().putInt("app_wallpaper_dim", value.coerceIn(0, 90)).apply()
    fun wallpaperDim(context: Context) = prefs(context).getInt("wallpaper_dim", 30).coerceIn(0, 80)
    fun wallpaperParallax(context: Context) = prefs(context).getBoolean("wallpaper_parallax", false)
    fun setWallpaperParallax(context: Context, value: Boolean) = prefs(context).edit().putBoolean("wallpaper_parallax", value).apply()
    fun wallpaperZoom(context: Context) = prefs(context).getInt("wallpaper_zoom", 100).coerceIn(100, 130)
    fun setWallpaperZoom(context: Context, value: Int) = prefs(context).edit().putInt("wallpaper_zoom", value.coerceIn(100, 130)).apply()
    fun wallpaperOffsetX(context: Context) = prefs(context).getInt("wallpaper_offset_x", 0).coerceIn(-50, 50)
    fun setWallpaperOffsetX(context: Context, value: Int) = prefs(context).edit().putInt("wallpaper_offset_x", value.coerceIn(-50, 50)).apply()
    fun wallpaperOffsetY(context: Context) = prefs(context).getInt("wallpaper_offset_y", 0).coerceIn(-50, 50)
    fun setWallpaperOffsetY(context: Context, value: Int) = prefs(context).edit().putInt("wallpaper_offset_y", value.coerceIn(-50, 50)).apply()
    fun wallpaperAmoled(context: Context) = prefs(context).getBoolean("wallpaper_amoled", false)
    fun setWallpaperAmoled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("wallpaper_amoled", value).apply()
    fun resetWallpaperStudio(context: Context) = prefs(context).edit()
        .remove("wallpaper_zoom").remove("wallpaper_offset_x").remove("wallpaper_offset_y")
        .remove("wallpaper_amoled").remove("wallpaper_dim").remove("wallpaper_parallax").apply()
    fun glassShimmer(context: Context) = prefs(context).getBoolean("glass_shimmer", false)
    fun setGlassShimmer(context: Context, value: Boolean) = prefs(context).edit().putBoolean("glass_shimmer", value).apply()
    fun experimentalAdaptiveGlass(context: Context) = prefs(context).getBoolean("experimental_adaptive_glass", false)
    fun setExperimentalAdaptiveGlass(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_adaptive_glass", value).apply()
    fun experimentalDynamicAccent(context: Context) = prefs(context).getBoolean("experimental_dynamic_accent", false)
    fun setExperimentalDynamicAccent(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_dynamic_accent", value).apply()
    fun experimentalAdaptiveNotifications(context: Context) = prefs(context).getBoolean("experimental_adaptive_notifications", false)
    fun setExperimentalAdaptiveNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_adaptive_notifications", value).apply()
    fun experimentalEnhancedHaptics(context: Context) = prefs(context).getBoolean("experimental_enhanced_haptics", false)
    fun setExperimentalEnhancedHaptics(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_enhanced_haptics", value).apply()
    fun experimentalAdaptiveTransition(context: Context) = prefs(context).getBoolean("experimental_adaptive_transition", false)
    fun setExperimentalAdaptiveTransition(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_adaptive_transition", value).apply()
    fun experimentalFrameworkTiming(context: Context) = prefs(context).getBoolean("experimental_framework_timing", false)
    fun setExperimentalFrameworkTiming(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_framework_timing", value).apply()
    fun experimentalLockMotion(context: Context) = prefs(context).getBoolean("experimental_lock_motion", false)
    fun setExperimentalLockMotion(context: Context, value: Boolean) = prefs(context).edit().putBoolean("experimental_lock_motion", value).apply()
    fun updateChannel(context: Context) = prefs(context).getString("update_channel", "preview") ?: "preview"
    fun setUpdateChannel(context: Context, value: String) = prefs(context).edit().putString("update_channel", value).apply()
    fun skippedUpdateTag(context: Context) = prefs(context).getString("skipped_update_tag", "") ?: ""
    fun setSkippedUpdateTag(context: Context, value: String) = prefs(context).edit().putString("skipped_update_tag", value).apply()
    fun lastUpdateCheck(context: Context) = prefs(context).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(context: Context, value: Long) = prefs(context).edit().putLong("last_update_check", value).apply()
    fun floatingBarScrollBehavior(context: Context) = prefs(context).getString("floating_bar_scroll_behavior", "hide_on_scroll") ?: "hide_on_scroll"
    fun setFloatingBarScrollBehavior(context: Context, value: String) = prefs(context).edit().putString("floating_bar_scroll_behavior", value).apply()
    fun doubleTapSleep(context: Context) = prefs(context).getBoolean("double_tap_sleep", false)
    fun setDoubleTapSleep(context: Context, value: Boolean) = prefs(context).edit().putBoolean("double_tap_sleep", value).apply()
    fun lockIdleSleepSeconds(context: Context) = prefs(context).getInt("lock_idle_sleep_seconds", 30).takeIf { it in listOf(0, 15, 30, 60, 120) } ?: 30
    fun setLockIdleSleepSeconds(context: Context, value: Int) = prefs(context).edit().putInt("lock_idle_sleep_seconds", value).apply()
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

    fun setupWizardComplete(context: Context) = prefs(context).getBoolean("setup_wizard_complete", false)
    fun setSetupWizardComplete(context: Context, value: Boolean) = prefs(context).edit().putBoolean("setup_wizard_complete", value).apply()
    fun setupWizardSeen(context: Context) = prefs(context).getBoolean("setup_wizard_seen", false)
    fun setSetupWizardSeen(context: Context, value: Boolean) = prefs(context).edit().putBoolean("setup_wizard_seen", value).apply()

    fun introSeenVersion(context: Context) = prefs(context).getInt("intro_seen_version", 0)
    fun setIntroSeenVersion(context: Context, value: Int) = prefs(context).edit().putInt("intro_seen_version", value).apply()
    fun introDismissed(context: Context) = prefs(context).getBoolean("intro_dismissed", false)
    fun setIntroDismissed(context: Context, value: Boolean) = prefs(context).edit().putBoolean("intro_dismissed", value).apply()
}
