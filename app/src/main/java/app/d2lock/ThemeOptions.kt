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
        fun label(value: String) = TextView(c).apply { text=value; textSize=15f; setTextColor(Appearance.text(c)); setPadding(0,dp(12),0,dp(6)) }
        fun choice(title: String, values: List<String>, selected: Int, key: String) {
            parent.addView(label(title))
            parent.addView(Spinner(c).apply {
                contentDescription=title
                adapter=object: ArrayAdapter<String>(c,android.R.layout.simple_spinner_dropdown_item,values) {
                    override fun getView(p:Int,v:View?,g:ViewGroup):View = (super.getView(p,v,g) as TextView).apply { setTextColor(Appearance.text(c)); setBackgroundColor(Appearance.surface(c)) }
                    override fun getDropDownView(p:Int,v:View?,g:ViewGroup):View = (super.getDropDownView(p,v,g) as TextView).apply { setTextColor(Appearance.text(c)); setBackgroundColor(Appearance.surface(c)) }
                }
                setSelection(selected)
                onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(p:AdapterView<*>?) {}
                    override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) {
                        if(position!=selected) { Appearance.set(c,key,position); refresh() }
                    }
                }
            },LinearLayout.LayoutParams(-1,dp(52)))
        }
        choice("App theme",listOf("Follow system","Light","Dark","AMOLED black"),Appearance.mode(c),"mode")
        choice("Accent color",listOf("System wallpaper","Blue","Teal","Lavender","Rose","Amber","Sage","Custom hex"),Appearance.accentChoice(c),"accent")
        if(Appearance.accentChoice(c)==7) parent.addView(Button(c).apply {
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
        choice("Notification colors",listOf("Different colors per app","Use accent color","Neutral"),Appearance.notificationStyle(c),"notifications")
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
            parent.addView(SeekBar(c).apply {
                contentDescription=title; this.max=max-min; progress=value-min
                progressTintList=ColorStateList.valueOf(Appearance.accent(c)); thumbTintList=progressTintList
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(s:SeekBar?) {}
                    override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean) {
                        if(user) {
                            Appearance.set(c,key,p+min); heading.text="$title: ${p+min}$suffix"
                            if(key.startsWith("bar_")) barPreview.background=Appearance.floatingBar(c)
                            else preview.background=Appearance.panel(c,"theme.preview",banner=key=="banners")
                        }
                    }
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            },LinearLayout.LayoutParams(-1,dp(48)))
        }
        slider("Card opacity","cards",20,100,Appearance.cardOpacity(c),"%")
        slider("Banner opacity","banners",40,100,Appearance.bannerOpacity(c),"%")
        slider("Corner radius","radius",8,36,Appearance.radius(c)," dp")
        parent.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(12) })
        parent.addView(label("Lower opacity shows more of the background. Wallpaper colors use Android's system palette. A custom lock wallpaper keeps light text for readability."))
        parent.addView(label("FLOATING LOCK-SCREEN BAR"))
        slider("Bar width","bar_width",65,100,Appearance.barWidth(c),"%")
        slider("Bar opacity","bar_opacity",40,100,Appearance.barOpacity(c),"%")
        slider("Distance above navigation area","bar_gap",8,72,Appearance.barGap(c)," dp")
        parent.addView(barPreview,LinearLayout.LayoutParams(-1,dp(64)))
        parent.addView(label("The bar uses your accent and keeps your existing shortcuts. Open the lock-screen preview to see its width and position."))
        parent.addView(Button(c).apply {
            text="Reset appearance"; setTextColor(Appearance.text(c))
            setOnClickListener { Appearance.reset(c); refresh() }
        },LinearLayout.LayoutParams(-1,dp(56)))
    }
}
