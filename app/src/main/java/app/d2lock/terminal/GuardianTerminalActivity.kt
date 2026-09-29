package app.d2lock.terminal
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import app.d2lock.Appearance
import app.d2lock.R
import app.d2lock.lockscreen.KeyguardSignalReceiver
import app.d2lock.root.RootManager
import rikka.shizuku.Shizuku

class GuardianTerminalActivity:Activity(){
 enum class Backend{ROOT,SHIZUKU,SYSTEM_SERVER,ZYGOTE}
 var backend=Backend.ROOT
 lateinit var out:TextView; lateinit var input:EditText; lateinit var state:TextView
 val logs=mutableMapOf<Backend,MutableList<String>>()
 fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onCreate(b:Bundle?){super.onCreate(b);Appearance.apply(this);setContentView(ui());refresh();banner()}
 fun ui()=ScrollView(this).apply{addView(LinearLayout(this@GuardianTerminalActivity).apply{
  orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(24))
  addView(TextView(this@GuardianTerminalActivity).apply{text="GUARDIAN TERMINAL";textSize=11f;letterSpacing=.12f;setTextColor(Appearance.accent(this@GuardianTerminalActivity))})
  addView(TextView(this@GuardianTerminalActivity).apply{text="Framework Command Center";textSize=25f;setTextColor(Appearance.text(this@GuardianTerminalActivity));setPadding(0,dp(2),0,dp(10))})
  state=TextView(this@GuardianTerminalActivity).apply{textSize=12f;setTextColor(Appearance.secondary(this@GuardianTerminalActivity));setPadding(dp(12),dp(10),dp(12),dp(10));background=Appearance.glass(this@GuardianTerminalActivity,22f,28,true)};addView(state)
  addView(LinearLayout(this@GuardianTerminalActivity).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(10),0,dp(10));Backend.entries.forEach{b->addView(TextView(this@GuardianTerminalActivity).apply{text=if(b==Backend.SYSTEM_SERVER)"SYSTEM" else b.name;textSize=10f;gravity=Gravity.CENTER;setTextColor(Appearance.text(this@GuardianTerminalActivity));background=Appearance.glass(this@GuardianTerminalActivity,18f,26,true);setPadding(dp(4),dp(10),dp(4),dp(10));setOnClickListener{backend=b;refresh();banner()}},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(2);rightMargin=dp(2)})}})
  out=TextView(this@GuardianTerminalActivity).apply{typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextColor(Appearance.text(this@GuardianTerminalActivity));setTextIsSelectable(true);minHeight=dp(330);setPadding(dp(14),dp(14),dp(14),dp(14));background=Appearance.glass(this@GuardianTerminalActivity,24f,34,true)};addView(out)
  addView(LinearLayout(this@GuardianTerminalActivity).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(10),0,0)
   input=EditText(this@GuardianTerminalActivity).apply{hint="Enter command…";isSingleLine=true;textSize=13f;setTextColor(Appearance.text(this@GuardianTerminalActivity));setHintTextColor(Appearance.secondary(this@GuardianTerminalActivity));background=Appearance.glass(this@GuardianTerminalActivity,22f,28,true);setPadding(dp(12),dp(10),dp(12),dp(10));setOnEditorActionListener{_,_,_->run();true}};addView(input,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(7)})
   addView(TextView(this@GuardianTerminalActivity).apply{text="RUN";gravity=Gravity.CENTER;textSize=12f;setTextColor(Appearance.text(this@GuardianTerminalActivity));background=Appearance.glass(this@GuardianTerminalActivity,20f,46,true);setPadding(dp(14),dp(12),dp(14),dp(12));setOnClickListener{run()}})
  })
 }})
 fun refresh(){val r=RootManager.isAvailable();val sh=runCatching{Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED}.getOrDefault(false);val sy=KeyguardSignalReceiver.systemHealth(this).status=="CONNECTED";state.text="ROOT ${if(r)"✓ ACTIVE" else "○ WAITING"}   ·   SHIZUKU ${if(sh)"✓ ACTIVE" else "○ WAITING"}\nSYSTEM SERVER ${if(sy)"✓ ACTIVE" else "○ WAITING"}   ·   ZYGOTE ${if(sy)"✓ BRIDGE READY" else "○ WAITING"}"}
 fun banner(){out.text=(logs[backend]?:mutableListOf()).joinToString("\n");add("— ${backend.name.replace('_',' ')} SESSION —");add(when(backend){Backend.ROOT->"KernelSU root shell · stdout/stderr + exit code";Backend.SHIZUKU->"Independent Shizuku diagnostics console";Backend.SYSTEM_SERVER->"Controlled LSPosed system_server console";Backend.ZYGOTE->"Controlled Zygote/LSPosed diagnostics console"})}
 fun add(v:String){logs.getOrPut(backend){mutableListOf()}.add(v);out.append(if(out.text.isEmpty())v else "\n$v")}
 fun run(){val cmd=input.text.toString().trim();if(cmd.isEmpty())return;input.text.clear();if(cmd=="clear"){logs[backend]?.clear();out.text="";banner();return};add("\nD2[${backend.name.lowercase()}]> $cmd");Thread{val x=when(backend){Backend.ROOT->RootManager.exec(cmd,15);Backend.SHIZUKU->console(cmd,false);Backend.SYSTEM_SERVER->console(cmd,true);Backend.ZYGOTE->zygote(cmd)};runOnUiThread{add(x);refresh()}}.start()}
 fun console(cmd:String,system:Boolean):String{if(!system&&!runCatching{Shizuku.pingBinder()}.getOrDefault(false))return "Shizuku is not connected.";val h=KeyguardSignalReceiver.systemHealth(this);val p=KeyguardSignalReceiver.pipelineMetrics(this);return when(cmd.lowercase()){"help"->if(system)"Commands: status, events, boot, hooks, fingerprint, help" else "Commands: status, binder, permission, framework, help";"status"->if(system)"system_server: ${h.status}\nlast event: ${h.event?:"none"}\nhook: ${h.method?:"none"}" else "binder: ${Shizuku.pingBinder()}\npermission: ${runCatching{Shizuku.checkSelfPermission()}.getOrNull()}";"events","boot"->p.timeline.joinToString("\n").ifBlank{"No framework pipeline events yet."};"hooks"->"system_server hook: ${h.method?:"waiting"}\nbridge: ${h.status}";"fingerprint"->"Fingerprint events are exposed through D2 framework capture.";"binder"->"Shizuku binder: ${Shizuku.pingBinder()}";"permission"->"Shizuku permission: ${runCatching{Shizuku.checkSelfPermission()}.getOrNull()}";"framework"->"System Server bridge: ${h.status} · ${h.event?:"waiting"}";else->"Unknown command. Type help."}}
 fun zygote(cmd:String):String{val h=KeyguardSignalReceiver.systemHealth(this);return when(cmd.lowercase()){"help"->"Commands: status, bridge, abi, process, help";"status","bridge"->"LSPosed framework bridge: ${h.status}\nSystem Server event: ${h.event?:"waiting"}";"abi"->"Supported ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\nProcess ABI: ${if(android.os.Process.is64Bit())"64-bit" else "32-bit"}";"process"->"D2 pid: ${android.os.Process.myPid()}\nuid: ${android.os.Process.myUid()}\nControlled diagnostics mode";else->"Unknown Zygote command. Type help."}}
}