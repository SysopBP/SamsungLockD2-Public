package app.d2lock.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.d2lock.Appearance
import app.d2lock.Prefs
import app.d2lock.root.RootManager
import rikka.shizuku.Shizuku

/**
 * Guardian Recovery is deliberately independent from the unlock credential path.
 * It reports recovery readiness and remains useful when privileged integrations disappear.
 */
class SeslPreviewActivity : Activity() {
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(app.d2lock.R.style.Theme_D2_SeslPreview)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        render()
    }

    private fun render() {
        val rootOk=RootManager.isAvailable()
        val shizukuRunning=runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted=shizukuRunning && runCatching {
            Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val kiosk=Prefs.kiosk(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(48),dp(22),dp(32))
            setBackgroundColor(Appearance.background(this@SeslPreviewActivity))
            addView(label("Guardian Recovery",30f,Appearance.text(this@SeslPreviewActivity)))
            addView(label("Fallback protection & recovery readiness",14f,Appearance.secondary(this@SeslPreviewActivity)).apply {
                setPadding(0,dp(4),0,dp(22))
            })
            addView(statusCard("Root / KernelSU",rootOk,
                if(rootOk) "Root shell available · privileged recovery ready"
                else "ROOT LOST · Guardian authentication remains independent"))
            addView(statusCard("LSPosed Bridge",false,
                "Bridge health is verified from SystemUI/module events · never required to unlock"))
            addView(statusCard("Shizuku",shizukuGranted,
                when { shizukuGranted -> "Service running · authorized"; shizukuRunning -> "Service running · permission not granted"; else -> "Service unavailable" }))
            addView(statusCard("Kiosk protection",kiosk,
                if(kiosk && rootOk) "Configured · privileged protection available"
                else if(kiosk) "Configured · root-loss fallback should avoid privileged reassert"
                else "Not enabled"))
            addView(card("Fallback policy",
                "PIN/pattern remains D2-owned. If root or LSPosed disappears, Guardian must preserve authentication, stop relying on privileged actions, and expose recovery instead of trapping the user."))
            addView(Button(this@SeslPreviewActivity).apply {
                text="Refresh recovery status"
                setOnClickListener { render() }
            })
            addView(Button(this@SeslPreviewActivity).apply {
                text="Copy diagnostics"
                setOnClickListener {
                    val report="Guardian Recovery\nRoot=$rootOk\nShizukuRunning=$shizukuRunning\nShizukuAuthorized=$shizukuGranted\nKiosk=$kiosk\nLSPosed=verify from D2XposedBridge/SystemUI log"
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Guardian diagnostics",report))
                }
            })
            addView(Button(this@SeslPreviewActivity).apply { text="Close"; setOnClickListener { finish() } })
        }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun statusCard(title:String,ok:Boolean,detail:String)=card(title,
        (if(ok) "Ready · " else "Attention · ")+detail)

    private fun card(title:String,description:String)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(18),dp(15),dp(18),dp(15))
        background=Appearance.glass(this@SeslPreviewActivity,28f,44,true)
        addView(label(title,18f,Appearance.text(this@SeslPreviewActivity)))
        addView(label(description,13f,Appearance.secondary(this@SeslPreviewActivity)).apply { setPadding(0,dp(3),0,dp(2)) })
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }
    }
    private fun label(value:String,size:Float,color:Int)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); gravity=Gravity.START
    }
}
