package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.widget.*
import android.view.View
import android.view.ViewGroup

object ThemeOptions {
    fun add(activity: Activity, parent: LinearLayout, refresh: () -> Unit) {
        val c = activity
        fun dp(v: Int) = (v*c.resources.displayMetrics.density).toInt()
        fun label(value: String) = TextView(c).apply { text=value; textSize=15f; setTextColor(Appearance.text(c)); setPadding(dp(8),dp(12),dp(8),dp(6)) }
        fun ui9Card() = Appearance.glass(c, 28f, 34, true)
        fun choice(title: String, values: List<String>, selected: Int, key: String) {
            parent.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = ui9Card()
                isClickable = true
                isFocusable = true
                addView(TextView(c).apply {
                    text = title
                    textSize = 16f
                    setTextColor(Appearance.text(c))
                })
                val current = TextView(c).apply {
                    text = values.getOrElse(selected) { values.firstOrNull().orEmpty() } + "   ›"
                    textSize = 13f
                    setTextColor(Appearance.secondary(c))
                    setPadding(0, dp(4), 0, 0)
                }
                addView(current)
                setOnClickListener {
                    val dialog = AlertDialog.Builder(c)
                        .setTitle(title)
                        .setSingleChoiceItems(values.toTypedArray(), selected) { d, which ->
                            if (which != selected) {
                                Appearance.set(c, key, which)
                                d.dismiss()
                                refresh()
                            } else d.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawable(Appearance.glass(c, 30f, 76, true))
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(c))
                    }
                    dialog.show()
                }
            }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(8) })
        }
        choice("App theme",listOf("Follow system","Light","Dark","AMOLED Black","One UI Dark","Graphite","Frosted Glass","Smoke","Wine Red","System (Dynamic)"),Appearance.mode(c),"mode")
        choice("Accent color",listOf("System wallpaper","Blue","Teal","Lavender","Rose","Amber","Sage","Wine Red","Deep Blue","Emerald","Purple","Custom color"),Appearance.accentChoice(c),"accent")
        if(Appearance.accentChoice(c)==11) parent.addView(Button(c).apply {
            text="Custom accent: #%06X".format(Appearance.custom(c) and 0xffffff)
            setTextColor(Appearance.text(c))
            setOnClickListener {
                val field=EditText(c).apply { setSingleLine(); hint="#RRGGBB"; setText("%06X".format(Appearance.custom(c) and 0xffffff)); filters=arrayOf(InputFilter.LengthFilter(7)) }
                val dialog=AlertDialog.Builder(c).setTitle("Custom accent").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create()
                dialog.setOnShowListener {
                    dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val hex=field.text.toString().trim().removePrefix("#")
                        if(!hex.matches(Regex("[0-9a-fA-F]{6}"))) field.error="Enter six hexadecimal digits"
                        else { Appearance.set(c,"custom",Color.parseColor("#$hex")); dialog.dismiss(); refresh() }
                    }
                }; dialog.show()
            }
        },LinearLayout.LayoutParams(-1,dp(56)))
        choice("Notification colors",listOf("Different colors per app","Use accent color","Monochrome","System colors","Custom color"),Appearance.notificationStyle(c),"notifications")
        val preview=TextView(c).apply {
            text="Notification preview\nA sample message in your chosen style"
            textSize=16f; setTextColor(Appearance.text(c,true)); setPadding(dp(16),dp(16),dp(16),dp(16))
            background=Appearance.panel(c,"theme.preview")
        }
        val barPreview=TextView(c).apply {
            text="Camera       PIN       Flashlight"; gravity=android.view.Gravity.CENTER
            setTextColor(Color.WHITE); background=Appearance.floatingBar(c)
            setPadding(dp(8),dp(16),dp(8),dp(16))
        }
        fun slider(title:String,key:String,min:Int,max:Int,value:Int,suffix:String) {
            val heading=label("$title: $value$suffix")
            parent.addView(heading)
            parent.addView(object : View(c) {
                private var sliderValue = value.coerceIn(min, max)
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                private val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = dp(2).toFloat()
                }

                init {
                    contentDescription = title
                    minimumHeight = dp(56)
                    isClickable = true
                    isFocusable = true
                }

                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val left = dp(10).toFloat()
                    val right = width - dp(10).toFloat()
                    val cy = height / 2f
                    val trackH = dp(14).toFloat()
                    val radius = trackH / 2f
                    val fraction = ((sliderValue - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val thumbX = left + (right - left) * fraction

                    // Same Guardian glass capsule used by the main settings sliders.
                    paint.color = if (Appearance.dark(c)) 0x4dffffff else 0x3dffffff
                    canvas.drawRoundRect(android.graphics.RectF(left, cy-trackH/2f, right, cy+trackH/2f), radius, radius, paint)
                    outline.color = if (Appearance.dark(c)) 0x66ffffff else 0x55000000
                    canvas.drawRoundRect(android.graphics.RectF(left, cy-trackH/2f, right, cy+trackH/2f), radius, radius, outline)
                    paint.color = 0x38ffffff
                    canvas.drawRoundRect(android.graphics.RectF(left+dp(2), cy-trackH/2f+dp(2), right-dp(2), cy-dp(1)), radius, radius, paint)

                    paint.color = (Appearance.accent(c) and 0x00ffffff) or 0x99000000.toInt()
                    if (thumbX > left) canvas.drawRoundRect(android.graphics.RectF(left, cy-trackH/2f, thumbX, cy+trackH/2f), radius, radius, paint)

                    paint.color = if (Appearance.dark(c)) 0x88ffffff.toInt() else 0xb8ffffff.toInt()
                    canvas.drawCircle(thumbX, cy, dp(16).toFloat(), paint)
                    outline.color = if (Appearance.dark(c)) 0xddffffff.toInt() else 0xcc000000.toInt()
                    canvas.drawCircle(thumbX, cy, dp(16).toFloat(), outline)
                    paint.color = 0x55ffffff
                    canvas.drawCircle(thumbX-dp(4), cy-dp(4), dp(4).toFloat(), paint)
                }

                private fun updateFromTouch(x: Float) {
                    val left = dp(10).toFloat()
                    val available = (width-dp(20)).coerceAtLeast(1)
                    val fraction = ((x-left)/available).coerceIn(0f,1f)
                    val next = min + kotlin.math.round(fraction*(max-min)).toInt()
                    if (next != sliderValue) {
                        sliderValue = next
                        Appearance.set(c,key,next)
                        heading.text="$title: $next$suffix"
                        if(key.startsWith("bar_")) barPreview.background=Appearance.floatingBar(c)
                        else preview.background=Appearance.panel(c,"theme.preview",banner=key=="banners")
                        invalidate()
                    }
                }

                override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                    when(e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); updateFromTouch(e.x); return true }
                        android.view.MotionEvent.ACTION_MOVE -> { updateFromTouch(e.x); return true }
                        android.view.MotionEvent.ACTION_UP -> { updateFromTouch(e.x); parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
                        android.view.MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
                    }
                    return super.onTouchEvent(e)
                }

                override fun performClick(): Boolean { super.performClick(); return true }
            },LinearLayout.LayoutParams(-1,dp(56)))
        }
        slider("Card opacity","cards",20,100,Appearance.cardOpacity(c),"%")
        slider("Banner opacity","banners",40,100,Appearance.bannerOpacity(c),"%")
        slider("Corner radius","radius",8,36,Appearance.radius(c)," dp")
        parent.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14); bottomMargin=dp(14) })
        parent.addView(label("Lower opacity shows more of the background. Wallpaper colors use Android's system palette. A custom lock wallpaper keeps light text for readability."))
        parent.addView(label("FLOATING LOCK-SCREEN BAR"))
        slider("Bar width","bar_width",65,100,Appearance.barWidth(c),"%")
        slider("Bar opacity","bar_opacity",40,100,Appearance.barOpacity(c),"%")
        slider("Distance above navigation area","bar_gap",8,72,Appearance.barGap(c)," dp")
        parent.addView(barPreview,LinearLayout.LayoutParams(-1,dp(64)))
        parent.addView(label("The bar uses your accent and keeps your existing shortcuts. Open the lock-screen preview to see its width and position."))
        parent.addView(LinearLayout(c).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(12))
            background=ui9Card()
            isClickable=true
            isFocusable=true
            addView(TextView(c).apply {
                text="Reset appearance   ›"
                textSize=16f
                setTextColor(Appearance.text(c))
            })
            addView(TextView(c).apply {
                text="Restore default layout, colors and settings"
                textSize=12f
                setTextColor(Appearance.secondary(c))
                setPadding(0,dp(3),0,0)
            })
            setOnClickListener { Appearance.reset(c); refresh() }
        },LinearLayout.LayoutParams(-1,dp(72)).apply { topMargin=dp(12); bottomMargin=dp(8) })
    }
}
