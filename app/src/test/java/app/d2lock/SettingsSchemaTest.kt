package app.d2lock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsSchemaTest {
    @Test fun compactPresentationCanBeRestoredWithoutRestoringProtection() {
        val backup = JSONObject().put("compact_layout", true).put("compact_details", false)
            .put("compact_bridge", true).put("enabled", true).put("root_kiosk", true)
        assertEquals(mapOf("compact_layout" to true, "compact_details" to false, "compact_bridge" to true), SettingsSchema.validate(backup))
        assertFalse(SettingsSchema.validStoredType("compact_layout", "true"))
        assertTrue(runCatching { SettingsSchema.validate(JSONObject().put("compact_details", 1)) }.isFailure)
    }

    @Test fun rejectsWrongTypesAndUnknownKeys() {
        for (raw in listOf("{\"wallpaper_dim\":true}", "{\"clock_scale\":1.5}", "{\"surprise\":true}")) {
            assertTrue(raw, runCatching { SettingsSchema.validate(JSONObject(raw)) }.isFailure)
        }
    }
    @Test fun protectedModesCannotBeRestoredEvenThroughProfiles() {
        val profile = JSONObject().put("root_kiosk", true).put("enabled", true).put("xposed_master", true).put("wallpaper_dim", 20)
        val values = SettingsSchema.validate(profile)
        assertEquals(mapOf("wallpaper_dim" to 20), values)
        val profiles = JSONObject().put("safe", profile)
        assertTrue(SettingsSchema.validate(JSONObject().put("custom_profiles", profiles.toString())).containsKey("custom_profiles"))
    }
    @Test fun rejectsRecursionAndInvalidNestedTypes() {
        for (snapshot in listOf(JSONObject().put("custom_profiles", "{}"), JSONObject().put("wallpaper_dim", false))) {
            val raw = JSONObject().put("custom_profiles", JSONObject().put("unsafe", snapshot).toString())
            assertTrue(runCatching { SettingsSchema.validate(raw) }.isFailure)
        }
    }
    @Test fun validatesAppearanceAndIconInsideNestedProfiles() {
        val snapshot = JSONObject().put("schema", 2).put("lock", JSONObject().put("wallpaper_dim", 20))
            .put("appearance", JSONObject().put("cards", true)).put("icon", "silver")
        val document = JSONObject().put("custom_profiles", JSONObject().put("unsafe", snapshot).toString())
        assertTrue(runCatching { SettingsSchema.validate(document) }.isFailure)
        snapshot.put("appearance", JSONObject().put("cards", 40)).put("icon", "purple")
        SettingsSchema.validateDocument(snapshot)
        snapshot.put("icon", "unknown")
        assertTrue(runCatching { SettingsSchema.validateDocument(snapshot) }.isFailure)
    }

}
