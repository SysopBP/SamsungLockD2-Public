package app.d2lock

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** D2-owned theme implementation using Android's public wallpaper color resources. */
object Appearance {
    private fun prefs(c: Context) = c.getSharedPreferences("d2_appearance", Context.MODE_PRIVATE)
    fun mode(c: Context) = prefs(c).getInt("mode", 2).coerceIn(0, 9)
    fun accentChoice(c: Context) = prefs(c).getInt("accent", 3).coerceIn(0, 11)
    fun notificationStyle(c: Context) = prefs(c).getInt("notifications", 0).coerceIn(0, 4)
    fun cardOpacity(c: Context) = prefs(c).getInt("cards", 40).coerceIn(20, 100)
    fun bannerOpacity(c: Context) = prefs(c).getInt("banners", 76).coerceIn(40, 100)
    fun radius(c: Context) = prefs(c).getInt("radius", 28).coerceIn(8, 36)
    fun barWidth(c: Context) = prefs(c).getInt("bar_width", 90).coerceIn(65, 100)
    fun barOpacity(c: Context) = prefs(c).getInt("bar_opacity", 85).coerceIn(40, 100)
    fun barGap(c: Context) = prefs(c).getInt("bar_gap", 24).coerceIn(8, 72)
    fun floatingBar(c: Context) = glass(c, 38f, barOpacity(c).coerceAtMost(58), true)
    /** One UI 9-inspired transparent glass surface shared by settings and lock screen. */
    fun glass(c: Context, radiusDp: Float = 28f, opacity: Int = 42, accentEdge: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * c.resources.displayMetrics.density
            val fill = blend(Color.BLACK, accent(c), if (accentEdge) .12f else .06f)
            setColor(((opacity.coerceIn(0, 100) * 255 / 100) shl 24) or (fill and 0xffffff))
            setStroke(c.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                (0x55 shl 24) or ((if (accentEdge) blend(Color.WHITE, accent(c), .35f) else Color.WHITE) and 0xffffff))
        }

    fun custom(c: Context) = prefs(c).getInt("custom", 0xff9a80be.toInt())
    fun set(c: Context, key: String, value: Int) { prefs(c).edit().putInt(key, value).apply() }
    fun reset(c: Context) { prefs(c).edit().clear().apply() }
    fun dark(c: Context, lock: Boolean = false): Boolean =
        (lock && Prefs.wallpaper(c) != null) || when(mode(c)) {
            // Follow system and System (Dynamic) both honor the current One UI
            // light/dark state. Fixed visual presets keep their explicit behavior.
            0, 9 -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            1 -> false
            else -> true
        }
    fun accent(c: Context): Int {
        // CORE owns the live accent. "System wallpaper" always follows the
        // current wallpaper/One UI palette; the experimental switch can also
        // opt fixed presets into wallpaper-derived accenting.
        val choice = accentChoice(c)
        // A manually selected custom accent is authoritative. Experimental
        // dynamic accent may decorate fixed presets, but must never mask the
        // color the user explicitly chose.
        if (choice == 0) {
            runCatching {
                val colors = android.app.WallpaperManager.getInstance(c)
                    .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                colors?.primaryColor?.toArgb()
            }.getOrNull()?.let { return it }
        }
        return when(choice) {
        0 -> c.getColor(android.R.color.system_accent1_400)
        1 -> 0xff608ec7.toInt()
        2 -> 0xff529f9c.toInt()
        3 -> 0xff9a80be.toInt()
        4 -> 0xffbd829e.toInt()
        5 -> 0xffc69a53.toInt()
        6 -> 0xff829286.toInt()
        7 -> 0xffa43b55.toInt()
        8 -> 0xff0b66b2.toInt()
        9 -> 0xff198754.toInt()
        10 -> 0xff7137a8.toInt()
        else -> custom(c) or 0xff000000.toInt()
        }
    }
    fun blend(a: Int, b: Int, amount: Float): Int = Color.rgb(
        (Color.red(a)*(1-amount)+Color.red(b)*amount).toInt(),
        (Color.green(a)*(1-amount)+Color.green(b)*amount).toInt(),
        (Color.blue(a)*(1-amount)+Color.blue(b)*amount).toInt())
    fun background(c: Context, lock: Boolean = false): Int = when {
        mode(c) == 3 -> Color.BLACK
        mode(c) == 4 -> 0xff0d1016.toInt()
        mode(c) == 5 -> 0xff171a20.toInt()
        mode(c) == 6 -> blend(0xff111722.toInt(), accent(c), .16f)
        mode(c) == 7 -> 0xff101419.toInt()
        mode(c) == 8 -> blend(0xff16080d.toInt(), 0xffa43b55.toInt(), .18f)
        mode(c) == 9 -> blend(0xff08131f.toInt(), c.getColor(android.R.color.system_accent1_400), .12f)
        dark(c, lock) -> blend(0xff10131c.toInt(), accent(c), .06f)
        else -> blend(0xfffaf9ff.toInt(), accent(c), .04f)
    }
    fun surface(c: Context) = blend(background(c), accent(c), if(dark(c)) .22f else .13f)
    fun text(c: Context, lock: Boolean = false) = if(dark(c, lock)) Color.WHITE else 0xff171923.toInt()
    fun secondary(c: Context, lock: Boolean = false) = blend(text(c, lock), background(c, lock), .24f)
    fun apply(activity: Activity) {
        activity.setTheme(if(dark(activity)) R.style.Theme_D2_Dark else R.style.Theme_SamsungLock)
        activity.window.navigationBarColor = background(activity)
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val decor = activity.window.decorView
        decor.post { decor.windowInsetsController?.setSystemBarsAppearance(if(dark(activity)) 0 else light, light) }
    }
    fun panel(c: Context, app: String?, banner: Boolean = false, call: Boolean = false): GradientDrawable {
        val palette = intArrayOf(0x608ec7, 0x529f9c, 0x9a80be, 0xbd829e)
        val rgb = when {
            app == null && !call -> 0x65738a // Never identify an app in Count only mode.
            notificationStyle(c) == 2 -> 0x65738a
            notificationStyle(c) == 3 -> c.getColor(android.R.color.system_accent1_400) and 0xffffff
            notificationStyle(c) == 4 -> custom(c) and 0xffffff
            notificationStyle(c) == 1 -> accent(c)
            call -> 0x579c7c
            else -> palette[Math.floorMod(app!!.hashCode(), palette.size)]
        }
        val fill = if(dark(c,true)) blend(Color.BLACK, rgb, .5f) else blend(Color.WHITE, rgb, .24f)
        val alpha = ((if(banner) bannerOpacity(c) else cardOpacity(c)) * 255 / 100)
        return GradientDrawable().apply {
            cornerRadius = radius(c) * c.resources.displayMetrics.density
            // Keep wallpaper visible: glass fill + subtle app/accent edge instead of an opaque card.
            setColor(((alpha.coerceAtMost(58)) shl 24) or (fill and 0xffffff))
            setStroke(c.resources.displayMetrics.density.toInt().coerceAtLeast(1), (0x66 shl 24) or (rgb and 0xffffff))
        }
    }
}
