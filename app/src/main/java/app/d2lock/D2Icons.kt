package app.d2lock

import android.widget.TextView

/** Scalable monochrome security glyphs; backgrounds remain controlled by Appearance. */
object D2Icons {
    fun applyTo(view: TextView, resource: Int, sizeDp: Int = 24) {
        val density = view.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val icon = view.context.getDrawable(resource)?.mutate()
        icon?.setTint(Appearance.text(view.context))
        icon?.setBounds(0, 0, size, size)
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawablePadding = (10 * density).toInt()
    }
}
