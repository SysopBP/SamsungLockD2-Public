/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared glass styling for feature sheets and their help/confirmation panels. */
object FeatureGlass {
    fun style(a: Activity,d: AlertDialog) {
        val density=a.resources.displayMetrics.density
        fun visit(v: View) {
            if(v is TextView) { v.setTextColor(Color.WHITE); if(v is Button) {
                v.isAllCaps=false
                v.background=Appearance.glass(a,18f,48,true)
                v.setPadding((14*density).toInt(),(10*density).toInt(),(14*density).toInt(),(10*density).toInt())
            } }
            if(v is ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        d.window?.let { w ->
            w.setBackgroundDrawable(Appearance.glass(a,32f,94,true));w.setDimAmount(.7f)
            w.setLayout(a.resources.displayMetrics.widthPixels-(32*density).toInt(),-2)
            visit(w.decorView)
            w.decorView.post {
                val max=(a.resources.displayMetrics.heightPixels*.82f).toInt()
                if(w.decorView.height>max) w.setLayout(a.resources.displayMetrics.widthPixels-(32*density).toInt(),max)
            }
        }
        for(id in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL))
            d.getButton(id)?.setTextColor(Appearance.accent(a))
    }
    fun help(a: Activity,title: String,text: String) {
        val view=TextView(a).apply { this.text=text;textSize=14f;setPadding(24,16,24,16) }
        val d=AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(view) }).setPositiveButton("Done",null).create()
        d.setOnShowListener { style(a,d) };d.show()
    }
}
