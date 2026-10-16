/* D2 adaptation of OneUIX ChargingLimitItem, b8f8144. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock
import android.app.Activity
import android.app.AlertDialog
import android.provider.Settings
import android.widget.*
import app.d2lock.root.ShellRunner
object ChargingLimitSettings {
 fun show(a: Activity) {
  val b=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
  var limit=if(Settings.Global.getInt(a.contentResolver,"protect_battery",0)==1) Settings.Global.getInt(a.contentResolver,"battery_protection_threshold",80).coerceIn(20,100) else 100
  val status=TextView(a).apply { text="Charging limit: $limit%\n100% disables Samsung battery protection. Root required; Samsung firmware applies the threshold." };b.addView(status)
  b.addView(SeekBar(a).apply { max=80;progress=limit-20;setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(s: SeekBar?,p: Int,user: Boolean) { limit=p+20;status.text="Charging limit: $limit%" }
   override fun onStartTrackingTouch(s: SeekBar?) {} ;override fun onStopTrackingTouch(s: SeekBar?) {}
  }) })
  val d=AlertDialog.Builder(a).setTitle("Charging limit").setView(b).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create()
  d.setOnShowListener { FeatureGlass.style(a,d);d.getButton(-1).setOnClickListener {
   val target=limit;d.getButton(-1).isEnabled=false
   Thread { val r=runCatching { ShellRunner.run(listOf("su","-c","settings put global battery_protection_threshold $target && settings put global protect_battery ${if(target==100) 0 else 1} && settings get global battery_protection_threshold && settings get global protect_battery"),4) }.getOrNull()
    val ok=r?.succeeded()==true && r.output.trim().lines()==listOf(target.toString(),if(target==100) "0" else "1")
    a.runOnUiThread { if(d.isShowing) { d.getButton(-1).isEnabled=true;status.text=if(ok) "Saved and read back: $target%. Test Samsung charging behavior." else "Could not save/read back. Check root permission." } }
   }.start()
  } };d.show()
 }
}
