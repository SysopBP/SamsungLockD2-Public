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
        options.forEach { option ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, context.packageName + ".launcher." + option.alias),
                if (option == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, selected.key).apply()
    }

    fun reset(context: Context) = apply(context, "purple")
}
