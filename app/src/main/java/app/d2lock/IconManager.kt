package app.d2lock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconManager {
    data class Option(val key: String, val label: String, val alias: String)

    val options = listOf(
        Option("purple", "Titanium Purple", "TitaniumPurple"),
        Option("blue", "Titanium Blue", "TitaniumBlue"),
        Option("red", "Titanium Red", "TitaniumRed"),
        Option("green", "Titanium Green", "TitaniumGreen"),
        Option("gold", "Titanium Gold", "TitaniumGold"),
        Option("silver", "Titanium Silver", "TitaniumSilver"),
        Option("amoled", "Titanium AMOLED", "TitaniumAmoled"),
    )

    private const val PREFS = "d2_launcher_icon"
    private const val KEY = "selected"

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "purple") ?: "purple"

    fun apply(context: Context, key: String) {
        val selected = options.firstOrNull { it.key == key } ?: options.first()
        val pm = context.packageManager
        // Enable the destination alias first. Disabling the currently active alias before
        // enabling its replacement can briefly leave the package with no LAUNCHER component,
        // which causes One UI Home to drop the app icon.
        val selectedComponent = ComponentName(
            context.packageName,
            context.packageName + ".launcher." + selected.alias
        )
        pm.setComponentEnabledSetting(
            selectedComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        options.filter { it != selected }.forEach { option ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, context.packageName + ".launcher." + option.alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // Samsung One UI Home can keep the old icon cached when aliases are toggled
        // with DONT_KILL_APP. Explicitly nudge the launcher package so it re-queries
        // the enabled launcher component without killing D2 itself.
        runCatching {
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            val launcher = pm.resolveActivity(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            if (!launcher.isNullOrBlank()) {
                context.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_PACKAGE_CHANGED)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                        .setPackage(launcher)
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, selected.key).apply()
    }

    fun reset(context: Context) = apply(context, "purple")
}
