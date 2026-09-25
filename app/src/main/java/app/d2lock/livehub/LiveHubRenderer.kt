package app.d2lock.livehub

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.d2lock.Appearance

class LiveHubRenderer(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun view(card: LiveHubCard): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = Appearance.glass(context, 30f, 34, true)
        contentDescription = listOf(card.title, card.subtitle).filter { it.isNotBlank() }.joinToString(", ")
        addView(TextView(context).apply {
            text = card.title
            textSize = 16f
            setTextColor(Appearance.text(context, true))
        })
        if (card.subtitle.isNotBlank()) addView(TextView(context).apply {
            text = card.subtitle
            textSize = 13f
            setTextColor(Appearance.secondary(context, true))
        })
        card.progress?.coerceIn(0f, 1f)?.let { value ->
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (value * max).toInt()
                isIndeterminate = false
            }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(8) })
        }
    }
}
