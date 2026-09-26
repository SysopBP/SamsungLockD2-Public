package app.d2lock.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import app.d2lock.Appearance

/**
 * Isolated UI compatibility surface.
 *
 * This activity intentionally has no Guardian, kiosk, lock-screen, PIN/pattern,
 * service, root or Shizuku dependencies. It is the safe landing zone for SESL9
 * controls before any production settings screen is migrated.
 */
class SeslPreviewActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Appearance.dark(this)) app.d2lock.R.style.Theme_D2_Dark else app.d2lock.R.style.Theme_SamsungLock)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(32))
            setBackgroundColor(Appearance.background(this@SeslPreviewActivity))
        }
        root.addView(TextView(this).apply {
            text = "SESL9 UI Preview · REAL"
            textSize = 30f
            setTextColor(Appearance.text(this@SeslPreviewActivity))
        })
        root.addView(TextView(this).apply {
            text = "Genuine SESL9 AppCompat controls · isolated from Guardian/authentication"
            textSize = 14f
            setTextColor(Appearance.secondary(this@SeslPreviewActivity))
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(card("Switch", "Samsung-style toggle candidate").apply {
            addView(SwitchCompat(this@SeslPreviewActivity).apply {
                text = "Preview toggle"
                isChecked = true
                setTextColor(Appearance.text(this@SeslPreviewActivity))
            })
        })

        root.addView(card("Slider", "Slim control candidate").apply {
            addView(AppCompatSeekBar(this@SeslPreviewActivity).apply {
                max = 100
                progress = 64
            })
        })

        root.addView(card("Choice row", "Dialog interaction candidate").apply {
            addView(Button(this@SeslPreviewActivity).apply {
                text = "Open choice dialog"
                setOnClickListener {
                    val choices = arrayOf("System", "Dark", "AMOLED")
                    AlertDialog.Builder(this@SeslPreviewActivity)
                        .setTitle("Preview theme")
                        .setSingleChoiceItems(choices, 1, null)
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        })

        root.addView(card("Radio controls", "Selection-row candidate").apply {
            listOf("Glass", "Monochrome", "Adaptive").forEachIndexed { index, label ->
                addView(AppCompatRadioButton(this@SeslPreviewActivity).apply {
                    text = label
                    isChecked = index == 0
                    setTextColor(Appearance.text(this@SeslPreviewActivity))
                })
            }
        })

        root.addView(Button(this).apply {
            text = "Close preview"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun card(title: String, description: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = Appearance.glass(this@SeslPreviewActivity, 28f, 44, true)
            addView(TextView(this@SeslPreviewActivity).apply {
                text = title
                textSize = 18f
                setTextColor(Appearance.text(this@SeslPreviewActivity))
            })
            addView(TextView(this@SeslPreviewActivity).apply {
                text = description
                textSize = 13f
                setTextColor(Appearance.secondary(this@SeslPreviewActivity))
                setPadding(0, dp(2), 0, dp(10))
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
}
