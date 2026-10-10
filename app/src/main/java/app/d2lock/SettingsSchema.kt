package app.d2lock

import org.json.JSONObject

/** Validate the complete document before any preferences are edited. */
object SettingsSchema {
    val protectedKeys = setOf("active_profile","adb_recovery","enabled","intro_dismissed","intro_seen_version","root_kiosk","root_mode","setup_wizard_complete","setup_wizard_seen","shizuku_enabled","xposed_automatic_fallback","xposed_bars_guard","xposed_calls","xposed_galaxy_island","xposed_gesture_coordination","xposed_home_recents","xposed_immediate_relock","xposed_island_calls","xposed_island_charging","xposed_island_fallback","xposed_island_guardian_sync","xposed_island_media","xposed_island_notifications","xposed_island_positioning","xposed_island_screen_state","xposed_master","xposed_media","xposed_native_lifecycle","xposed_notifications","xposed_safe_diagnostics","xposed_screen_awareness","xposed_systemui_recovery")
    private val types = mapOf(
        "app_wallpaper" to "String",
        "app_wallpaper_dim" to "Int",
        "celsius" to "Boolean",
        "clock_adaptive" to "Boolean",
        "clock_layout" to "String",
        "clock_scale" to "Int",
        "clock_style" to "String",
        "double_tap_sleep" to "Boolean",
        "experimental_adaptive_glass" to "Boolean",
        "experimental_adaptive_notifications" to "Boolean",
        "experimental_adaptive_transition" to "Boolean",
        "experimental_dynamic_accent" to "Boolean",
        "experimental_enhanced_haptics" to "Boolean",
        "experimental_framework_timing" to "Boolean",
        "experimental_lock_motion" to "Boolean",
        "floating_bar_scroll_behavior" to "String",
        "floating_unlock_gesture" to "String",
        "glass_media" to "Int",
        "glass_shimmer" to "Boolean",
        "glass_top_info" to "Int",
        "last_update_check" to "Long",
        "live_notifications" to "Boolean",
        "lock_glass" to "Int",
        "lock_idle_sleep_seconds" to "Int",
        "lock_scale" to "Int",
        "media_buttons_scale" to "Int",
        "media_layout" to "String",
        "notification_density" to "String",
        "notification_glass" to "Int",
        "notification_privacy" to "Int",
        "notification_radius" to "Int",
        "quick_settings_guard" to "Boolean",
        "shortcut_left" to "String",
        "shortcut_right" to "String",
        "show_battery_percent" to "Boolean",
        "show_date" to "Boolean",
        "show_media" to "Boolean",
        "show_weather" to "Boolean",
        "skipped_update_tag" to "String",
        "top_info_size" to "Int",
        "unlock_haptics" to "String",
        "unlock_method" to "String",
        "update_channel" to "String",
        "wallpaper" to "String",
        "wallpaper_amoled" to "Boolean",
        "wallpaper_dim" to "Int",
        "wallpaper_offset_x" to "Int",
        "wallpaper_offset_y" to "Int",
        "wallpaper_parallax" to "Boolean",
        "wallpaper_zoom" to "Int",
        "weather_location" to "String"
    )
    fun validateDocument(document: JSONObject, allowProfiles: Boolean = true): Map<String, Any> {
        if (!document.has("schema")) return validate(document, allowProfiles)
        require(document.get("schema") == 2) { "Unsupported backup schema" }
        require(document.keys().asSequence().all { it in setOf("schema", "lock", "appearance", "icon") }) { "Unknown backup section" }
        val values = validate(document.getJSONObject("lock"), allowProfiles)
        val appearance = document.getJSONObject("appearance")
        val keys = setOf("mode", "accent", "notifications", "cards", "banners", "radius", "bar_width", "bar_opacity", "bar_gap", "custom")
        for (key in appearance.keys()) {
            val value = appearance.get(key)
            require(key in keys && value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "Invalid appearance setting: $key" }
        }
        require(document.get("icon") in setOf("silver", "red", "blue", "purple", "green", "gold", "amoled")) { "Invalid launcher icon" }
        return values
    }
    fun validStoredType(key: String, value: Any?): Boolean {
        val expected = when {
            key == "custom_profiles" || key == "active_profile" -> "String"
            key == "intro_seen_version" -> "Int"
            key in protectedKeys -> "Boolean"
            else -> types[key] ?: return false
        }
        return when (expected) {
            "Boolean" -> value is Boolean
            "Int" -> value is Int
            "Long" -> value is Long
            "Float" -> value is Float
            else -> value is String
        }
    }
    fun validate(json: JSONObject, allowProfiles: Boolean = true): Map<String, Any> {
        require(json.toString().length <= 524288) { "Backup is too large" }
        val result = linkedMapOf<String, Any>()
        for (key in json.keys()) {
            if (key in protectedKeys) continue
            val value = json.get(key)
            if (key == "custom_profiles") {
                require(allowProfiles && value is String) { "Invalid nested profiles" }
                val profiles = JSONObject(value as String)
                require(profiles.length() <= 32) { "Too many profiles" }
                for (name in profiles.keys()) {
                    require(name.isNotBlank() && name.length <= 32) { "Invalid profile name" }
                    val snapshot = profiles.getJSONObject(name)
                    if (snapshot.has("schema")) {
                        validateDocument(snapshot, false)
                    } else validate(snapshot, false)
                }
                result[key] = value
                continue
            }
            val type = types[key] ?: throw IllegalArgumentException("Unknown setting: $key")
            val valid = when (type) {
                "Boolean" -> value is Boolean
                "Int" -> value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()
                "Long" -> value is Long || value is Int
                "Float" -> value is Number && value.toDouble().isFinite()
                else -> value is String && value.length <= 8192
            }
            require(valid) { "Invalid type for $key" }
            result[key] = when (type) { "Int" -> (value as Number).toInt(); "Long" -> (value as Number).toLong(); "Float" -> (value as Number).toFloat(); else -> value }
        }
        return result
    }
}
