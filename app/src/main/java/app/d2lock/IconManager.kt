package app.d2lock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

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
        val selectedComponent = component(context, selected)

        // Store first so a process restart caused by a launcher/package refresh cannot
        // revert the selection to the previous icon.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selected.key).commit()

        // Explicitly enable the destination first, then disable every other alias.
        // COMPONENT_ENABLED_STATE_DEFAULT is deliberately avoided: TitaniumPurple is
        // enabled in the manifest, so resetting it to DEFAULT can make it reappear.
        pm.setComponentEnabledSetting(
            selectedComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        options.filterNot { it.key == selected.key }.forEach { option ->
            pm.setComponentEnabledSetting(
                component(context, option),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // A second pass makes the final package-manager state deterministic on One UI.
        options.forEach { option ->
            val wanted = if (option.key == selected.key)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val name = component(context, option)
            if (pm.getComponentEnabledSetting(name) != wanted) {
                pm.setComponentEnabledSetting(name, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }

    /** PackageManager alias states for diagnosing One UI launcher caching. */
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
