/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock
import android.app.Activity
import android.app.AlertDialog
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
object PowerActionSettings {
 fun show(a: Activity,state: JSONObject) {
  val items=state.getJSONArray("powerMenuActions");val rows=(0 until items.length()).map { items.getJSONObject(it) }.toMutableList()
  val body=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10) }
  fun render() { body.removeAllViews();body.addView(TextView(a).apply { text="Enable Custom power menu in the previous panel, then Apply. Emergency call and medical information remain visible. D2 SRP status and Lock D2 stay available. Restart actions require confirmation and fresh SRP verification." })
   rows.forEachIndexed { index,item ->
    val row=LinearLayout(a)
    row.addView(Switch(a).apply { text=item.getString("name").replace('_',' ');isChecked=item.optBoolean("visible",true)
      isEnabled=item.getString("name") !in setOf("emergency_call","medical_info")
      if(!isEnabled) { isChecked=true;item.put("visible",true) };setOnCheckedChangeListener { _,v -> item.put("visible",v) }
    },LinearLayout.LayoutParams(0,-2,1f))
    row.addView(Button(a).apply { text="↑";isEnabled=index>0;setOnClickListener { java.util.Collections.swap(rows,index,index-1);render();FeatureGlass.style(a,dialog!!) } });body.addView(row)
   }
   state.put("powerMenuActions",JSONArray(rows))
  }
  render();val d=AlertDialog.Builder(a).setTitle("Power actions").setView(ScrollView(a).apply { addView(body) }).setPositiveButton("Done",null).create();dialog=d
  d.setOnShowListener { FeatureGlass.style(a,d) };d.setOnDismissListener { dialog=null };d.show()
 }
 private var dialog: AlertDialog?=null
}
