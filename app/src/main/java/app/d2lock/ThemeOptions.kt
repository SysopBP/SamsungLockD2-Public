package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.Paint

object ThemeOptions {
    fun add(activity: Activity, parent: LinearLayout, refresh: () -> Unit) {
        val c = activity
        fun dp(v: Int) = (v*c.resources.displayMetrics.density).toInt()
        fun label(value: String) = TextView(c).apply { text=value; textSize=15f; setTextColor(Appearance.text(c)); setPadding(dp(8),dp(12),dp(8),dp(6)) }
        fun ui9Card() = Appearance.glass(c, 28f, 34, true)
        fun description(value: String) = TextView(c).apply {
            text = value
            textSize = 12f
            setTextColor(Appearance.secondary(c))
            setPadding(0, dp(3), 0, 0)
        }
        val choiceDescriptions = mapOf(
            "App theme" to "Choose the overall look for Kiosk D2 Guardian.",
            "Accent color" to "Used for highlights, sliders and buttons.",
            "Notification colors" to "Choose how notification colors are applied."
        )
        fun choice(title: String, values: List<String>, selected: Int, key: String) {
            // Match the compact Haptics preference: one clean row, then a
            // single-choice Guardian glass sheet instead of an expanded list.
            parent.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(16), 0)
                background = ui9Card()
                isClickable = true
                isFocusable = true
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(c).apply {
                        text = title
                        textSize = 16f
                        setTextColor(Appearance.text(c))
                    })
                    choiceDescriptions[title]?.let { addView(description(it)) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(c).apply {
                    text = values.getOrElse(selected) { values.firstOrNull().orEmpty() } + "  ›"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setTextColor(Appearance.text(c))
                    background = Appearance.glass(c, 22f, 26, true)
                }, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener {
                    val dialog = AlertDialog.Builder(c)
                        .setTitle(title)
                        .setSingleChoiceItems(values.toTypedArray(), selected) { d, which ->
                            if (which != selected) Appearance.set(c, key, which)
                            d.dismiss()
                            if (which != selected) refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawable(Appearance.glass(c, 30f, 76, true))
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(c))
                    }
                    dialog.show()
                }
            }, LinearLayout.LayoutParams(-1, dp(78)).apply { bottomMargin = dp(10) })
        }
        choice("App theme",listOf("Follow system","Light","Dark","AMOLED Black","One UI Dark","Graphite","Frosted Glass","Smoke","Wine Red","System (Dynamic)"),Appearance.mode(c),"mode")
        choice("Accent color",listOf("System wallpaper","Blue","Teal","Lavender","Rose","Amber","Sage","Wine Red","Deep Blue","Emerald","Purple","Custom color"),Appearance.accentChoice(c),"accent")
        if(Appearance.accentChoice(c)==11) parent.addView(TextView(c).apply {
            text="Custom accent   #%06X   ›".format(Appearance.custom(c) and 0xffffff)
            textSize=14f
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18),0,dp(18),0)
            setTextColor(Appearance.text(c))
            background=Appearance.glass(c,22f,28,true)
            isClickable=true
            isFocusable=true
            setOnClickListener {
                val field=EditText(c).apply { setSingleLine(); hint="#RRGGBB"; setText("%06X".format(Appearance.custom(c) and 0xffffff)); filters=arrayOf(InputFilter.LengthFilter(7)) }
                val dialog=AlertDialog.Builder(c).setTitle("Custom accent").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create()
                dialog.setOnShowListener {
                    dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.window?.setBackgroundDrawable(Appearance.glass(c,30f,76,true))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(c))
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(c))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val hex=field.text.toString().trim().removePrefix("#")
                        if(!hex.matches(Regex("[0-9a-fA-F]{6}"))) field.error="Enter six hexadecimal digits"
                        else { Appearance.set(c,"custom",Color.parseColor("#$hex")); dialog.dismiss(); refresh() }
                    }
                }; dialog.show()
            }
        },LinearLayout.LayoutParams(-1,dp(50)).apply { leftMargin=dp(8); rightMargin=dp(8); bottomMargin=dp(10) })
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
        class GlassThemeSlider(context: android.content.Context) : SeekBar(context) {
            private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
            private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=dp(1).toFloat() }
            init {
                progressDrawable=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                thumb=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                splitTrack=false
                minimumHeight=dp(44)
                setPadding(dp(12),0,dp(12),0)
            }
            override fun onDraw(canvas: Canvas) {
                val left=paddingLeft.toFloat()
                val right=(width-paddingRight).toFloat()
                val cy=height/2f
                val railH=dp(4).toFloat()
                val radius=railH/2f
                val fraction=if(max>0) progress.toFloat()/max.toFloat() else 0f
                val x=left+(right-left)*fraction
                fill.style=Paint.Style.FILL
                fill.color=if(Appearance.dark(c)) 0x30ffffff else 0x24000000
                canvas.drawRoundRect(left,cy-railH/2f,right,cy+railH/2f,radius,radius,fill)
                outline.color=if(Appearance.dark(c)) 0x55ffffff else 0x44000000
                canvas.drawRoundRect(left,cy-railH/2f,right,cy+railH/2f,radius,radius,outline)
                if(x>left) {
                    fill.color=(Appearance.accent(c) and 0x00ffffff) or 0xb8000000.toInt()
                    canvas.drawRoundRect(left,cy-railH/2f,x,cy+railH/2f,radius,radius,fill)
                }
                val thumbR=dp(8).toFloat()
                fill.color=(Appearance.accent(c) and 0x00ffffff) or 0xe6000000.toInt()
                canvas.drawCircle(x,cy,thumbR,fill)
                outline.color=0x99ffffff.toInt()
                canvas.drawCircle(x,cy,thumbR,outline)
                fill.color=0x42ffffff
                canvas.drawCircle(x-dp(3),cy-dp(3),dp(2).toFloat(),fill)
            }
        }
        fun slider(title:String,key:String,min:Int,max:Int,value:Int,suffix:String) {
            var currentValue=value.coerceIn(min,max)
            val heading=label("$title: $currentValue$suffix")
            parent.addView(heading)
            val glassSlider=LinearLayout(c).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                setPadding(0,0,0,0)
                background=null
            }
            glassSlider.addView(GlassThemeSlider(c).apply {
                this.max=(max-min).coerceAtLeast(1)
                progress=currentValue-min
                contentDescription=title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if(!fromUser) return
                        val next=min+progress
                        if(next==currentValue) return
                        currentValue=next
                        Appearance.set(c,key,next)
                        heading.text="$title: $next$suffix"
                        if(key.startsWith("bar_")) barPreview.background=Appearance.floatingBar(c)
                        else preview.background=Appearance.panel(c,"theme.preview",banner=key=="banners")
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?)=Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?)=Unit
                })
            },LinearLayout.LayoutParams(-1,dp(44)))
            parent.addView(glassSlider,LinearLayout.LayoutParams(-1,dp(44)).apply {
                topMargin=0
                bottomMargin=dp(8)
            })
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
