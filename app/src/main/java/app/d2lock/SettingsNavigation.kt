package app.d2lock

/** Every original section has a destination; new sections default to Tools. */
object SettingsNavigation {
    fun pageForSection(title: String): String = when(title) {
        "SECURITY CENTER", "FINGERPRINT CENTER", "LOCK-SCREEN PROFILES" -> "security"
        "LOCK & WAKE", "HOME SCREEN WIDGET", "GUARDIAN & KIOSK", "NOTIFICATIONS", "ACCESS CENTER", "MEDIA" -> "guardian"
        "THEME & COLORS", "ICON CENTER", "APP THEME", "WALLPAPER CENTER", "FLOATING BAR", "LOCK SCREEN EXTRAS", "CLOCK & WEATHER" -> "appearance"
        else -> "tools"
    }
}
