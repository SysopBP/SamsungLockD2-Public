package app.d2lock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object IconManager {
    data class Option(val key: String, val label: String, val alias: String)

    val options = listOf(
        Option("silver", "Titanium Graphite", "TitaniumSilver"),
        Option("red", "Wine Red", "TitaniumRed"),
        Option("blue", "Samsung Blue", "TitaniumBlue"),
        Option("purple", "Titanium Purple", "TitaniumPurple"),
        Option("green", "Emerald", "TitaniumGreen"),
        Option("gold", "Titanium Gold", "TitaniumGold"),
        Option("amoled", "AMOLED Black", "TitaniumAmoled"),
    )

    private const val PREFS = "d2_launcher_icon"
    private const val KEY = "selected"

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "silver") ?: "silver"

    fun apply(context: Context, key: String) {
        val selected = options.firstOrNull { it.key == key } ?: options.first()
        val pm = context.packageManager

        // Persist before touching launcher components so a package refresh/process restart
        // cannot restore the previous selection.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selected.key).commit()

        // API 33+ can change all aliases atomically. This avoids One UI briefly seeing
        // multiple launcher activities (or none), which can leave its cached icon stale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val settings = options.map { option ->
                PackageManager.ComponentEnabledSetting(
                    component(context, option),
                    if (option.key == selected.key)
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.SYNCHRONOUS
                )
            }
            pm.setComponentEnabledSettings(settings)
        } else {
            // Older Android fallback: enable destination first, then synchronously disable
            // the remaining launcher aliases.
            pm.setComponentEnabledSetting(
                component(context, selected),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.SYNCHRONOUS
            )
            options.filterNot { it.key == selected.key }.forEach { option ->
                pm.setComponentEnabledSetting(
                    component(context, option),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.SYNCHRONOUS
                )
            }
        }
    }

    /** Current package-manager alias states, useful when diagnosing launcher caching. */
    fun diagnostic(context: Context): String {
        val pm = context.packageManager
        return options.joinToString(" | ") { option ->
            val state = pm.getComponentEnabledSetting(component(context, option))
            "${option.key}=$state"
        }
    }

    fun reset(context: Context) = apply(context, "silver")

    private fun component(context: Context, option: Option) = ComponentName(
        context.packageName,
        context.packageName + ".launcher." + option.alias
    )
}
