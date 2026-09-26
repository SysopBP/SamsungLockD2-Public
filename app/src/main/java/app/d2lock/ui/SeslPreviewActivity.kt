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
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
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
        setTheme(app.d2lock.R.style.Theme_D2_SeslPreview)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(32))
            setBackgroundColor(Appearance.background(this@SeslPreviewActivity))
        }
        root.addView(TextView(this).apply {
            text = "SESL9 × Material 3 Preview"
            textSize = 30f
            setTextColor(Appearance.text(this@SeslPreviewActivity))
        })
        root.addView(TextView(this).apply {
            text = "On-device comparison · isolated from Guardian/authentication"
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

        root.addView(card("Material 3 slider", "Compare geometry, thumb, track and theming on-device").apply {
            addView(Slider(this@SeslPreviewActivity).apply {
                valueFrom = 0f
                valueTo = 100f
                value = 64f
            })
        })

        root.addView(card("Material 3 switch", "Compare against the SESL9 switch above").apply {
            addView(MaterialSwitch(this@SeslPreviewActivity).apply {
                text = "Material preview toggle"
                isChecked = true
                setTextColor(Appearance.text(this@SeslPreviewActivity))
            })
        })

        root.addView(card("Material 3 radio", "Compare selection styling before any production migration").apply {
            addView(MaterialRadioButton(this@SeslPreviewActivity).apply {
                text = "Material glass candidate"
                isChecked = true
                setTextColor(Appearance.text(this@SeslPreviewActivity))
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
