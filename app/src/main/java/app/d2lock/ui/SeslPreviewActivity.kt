package app.d2lock.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import app.d2lock.Appearance
import app.d2lock.Prefs
import app.d2lock.root.RootManager
import rikka.shizuku.Shizuku

/**
 * Guardian Recovery remains independent from the unlock credential path.
 * This screen modernizes presentation only; recovery behavior is unchanged.
 */
class SeslPreviewActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(app.d2lock.R.style.Theme_D2_SeslPreview)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        render()
    }

    private fun render() {
        val rootOk = RootManager.isAvailable()
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val kiosk = Prefs.kiosk(this)
        val bridgeHealth = getSharedPreferences("guardian_xposed_health", Context.MODE_PRIVATE)
        val bridgeLastEvent = bridgeHealth.getLong("last_systemui_event_ms", 0L)
        val bridgeAgeMs = if (bridgeLastEvent > 0L) System.currentTimeMillis() - bridgeLastEvent else Long.MAX_VALUE
        val bridgeReady = Prefs.xposedMaster(this) && bridgeAgeMs <= 10 * 60 * 1000L
        val bridgeChip = if (bridgeReady) "READY" else "WAITING"
        val bridgeDetail = when {
            !Prefs.xposedMaster(this) -> "Integration disabled · enable LSPosed integration to verify SystemUI"
            bridgeLastEvent == 0L -> "Waiting for first verified SystemUI/module event"
            bridgeReady -> "Verified SystemUI/module heartbeat · never required to unlock"
            else -> "Last SystemUI heartbeat is stale · restart SystemUI or soft reboot"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(42), dp(22), dp(72))
            setBackgroundColor(Appearance.background(this@SeslPreviewActivity))
            addView(label("Guardian Recovery", 30f, Appearance.text(this@SeslPreviewActivity)))
            addView(label("Fallback protection & recovery readiness", 14f, Appearance.secondary(this@SeslPreviewActivity)).apply {
                setPadding(0, dp(4), 0, dp(18))
            })
            addView(statusCard("Root / KernelSU", rootOk,
                if (rootOk) "Root shell available · privileged recovery ready"
                else "Root unavailable · D2 authentication remains independent"))
            addView(statusCard("LSPosed Bridge", bridgeReady,
                bridgeDetail, bridgeChip))
            addView(statusCard("Shizuku", shizukuGranted,
                when {
                    shizukuGranted -> "Service running · authorized"
                    shizukuRunning -> "Service running · permission not granted"
                    else -> "Service unavailable"
                }))
            addView(statusCard("Kiosk protection", kiosk,
                if (kiosk && rootOk) "Configured · privileged protection available"
                else if (kiosk) "Configured · privileged reassert disabled without root"
                else "Not enabled"))

            addView(infoCard("Fallback policy",
                "D2 keeps PIN/pattern ownership even if root or LSPosed disappears. Privileged actions stop and recovery remains available."))

            addView(primaryAction("Refresh recovery status") { render() })
            val secondary = LinearLayout(this@SeslPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tonalAction("Copy diagnostics") {
                    val report = "Guardian Recovery\nRoot=$rootOk\nShizukuRunning=$shizukuRunning\nShizukuAuthorized=$shizukuGranted\nKiosk=$kiosk\nLSPosedReady=$bridgeReady\nLSPosedLastEventMs=$bridgeLastEvent"
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Guardian diagnostics", report))
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
                addView(tonalAction("Close") { finish() },
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
            }
            addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(root)
        })
    }

    private fun statusCard(title: String, ok: Boolean, detail: String, forcedChip: String? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = Appearance.glass(this@SeslPreviewActivity, 30f, 46, true)
            val head = LinearLayout(this@SeslPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 17f, Appearance.text(this@SeslPreviewActivity)),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip(forcedChip ?: if (ok) "READY" else "ATTENTION"))
            }
            addView(head)
            addView(label(detail, 13f, Appearance.secondary(this@SeslPreviewActivity)).apply {
                setPadding(0, dp(6), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }

    private fun infoCard(title: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = Appearance.glass(this@SeslPreviewActivity, 30f, 34, true)
        addView(label("ⓘ  $title", 16f, Appearance.text(this@SeslPreviewActivity)))
        addView(label(detail, 13f, Appearance.secondary(this@SeslPreviewActivity)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun chip(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = .06f
        gravity = Gravity.CENTER
        setTextColor(Appearance.text(this@SeslPreviewActivity))
        setPadding(dp(11), dp(6), dp(11), dp(6))
        background = Appearance.glass(this@SeslPreviewActivity, 18f, 58, true)
    }

    private fun primaryAction(value: String, action: () -> Unit) = AppCompatButton(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setTextColor(Appearance.text(this@SeslPreviewActivity))
        background = Appearance.glass(this@SeslPreviewActivity, 26f, 72, true)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }

    private fun tonalAction(value: String, action: () -> Unit) = AppCompatButton(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(Appearance.text(this@SeslPreviewActivity))
        background = Appearance.glass(this@SeslPreviewActivity, 24f, 42, true)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
    }
}
