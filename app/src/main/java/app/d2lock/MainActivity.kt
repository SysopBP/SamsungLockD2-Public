package app.d2lock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.SystemClock
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.lockscreen.LockScreenService
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi
import app.d2lock.security.PatternStore
import app.d2lock.security.PatternUi
import app.d2lock.passkeys.D2PasskeyStore
import app.d2lock.widget.DoubleTap
import app.d2lock.widget.D2Widget
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val wallpaperPicker = 701
    private var authorized = false
    private var pinDialog: AlertDialog? = null
    private val shizukuRequestCode = 910
    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuUi() }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener { refreshShizukuUi() }
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == shizukuRequestCode) refreshShizukuUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Appearance.dark(this)) R.style.Theme_D2_Dark else R.style.Theme_SamsungLock)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        PinUi.protect(this)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(TextView(this).apply { text = "Samsung Lock D2" })
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
    }

    override fun onResume() {
        super.onResume()
        if (!authorized && pinDialog?.isShowing != true) {
            if (PinStore(this).configured()) {
                pinDialog = PinUi.show(this, success = {
                    authorized = true
                    app.d2lock.bridge.IslandBridge.setLocked(this, false)
                    if (Prefs.enabled(this)) {
                        runCatching { LockScreenService.start(this) }
                    }
                    setContentView(buildSettings())
                }, cancel = { finish() })
            } else {
                // Upgrades must not leave the old unprotected service enabled.
                Prefs.setEnabled(this, false)
                LockScreenService.stop(this)
                setContentView(buildSettings())
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        authorized = false
        pinDialog?.dismiss()
        pinDialog = null
        setContentView(TextView(this).apply { text = "D2 settings locked" })
    }

    private fun buildSettings(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(32))
            setBackgroundColor(Appearance.background(this@MainActivity))
        }
        root.addView(TextView(this).apply {
            text = "Samsung Lock D2"
            textSize = 32f
            setTextColor(Appearance.text(this@MainActivity))
        })
        root.addView(TextView(this).apply {
            text = "D2 Security Session · Independent app authentication"
            textSize = 15f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(0, dp(4), 0, dp(24))
        })

        section(root, "SETTINGS")
        val categories = listOf(
            Triple("Main", "Security, access & preview", "main_settings"),
            Triple("Clock & Weather", "Clock style, global weather & location", "clock_weather"),
            Triple("Notifications", "Privacy, banners & card appearance", "notifications"),
            Triple("App Theme", "Glass, wallpaper, scale & media", "app_theme"),
            Triple("Floating Bar", "Left and right lock-screen actions", "floating_bar")
        )
        categories.forEach { (label, description, target) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(13), dp(18), dp(13))
                background = Appearance.glass(this@MainActivity, 30f, 36, true)
                addView(TextView(this@MainActivity).apply {
                    text = "$label   ›"
                    textSize = 17f
                    setTextColor(Appearance.text(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    textSize = 13f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener {
                    root.findViewWithTag<android.view.View>(target)?.let { view ->
                        (root.parent as? ScrollView)?.smoothScrollTo(0, view.top)
                    }
                }
            }
            root.addView(card, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(8) })
        }

        addButton(root, "Licenses and credits") {
            val notice = assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            val text = TextView(this).apply {
                this.text = "D2 Project — All rights reserved.\n\nWeather data and geocoding: Open-Meteo.\n\n$notice"
                textSize = 14f
                setTextColor(Appearance.text(this@MainActivity))
                setLineSpacing(0f, 1.12f)
                setPadding(dp(18), dp(14), dp(18), dp(18))
                setTextIsSelectable(true)
            }
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                addView(text)
                background = Appearance.glass(this@MainActivity, 24f, 30, true)
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(8))
                addView(scroll, LinearLayout.LayoutParams(-1, dp(560)))
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Licenses and credits")
                .setView(content)
                .setPositiveButton("Close", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 78, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(Appearance.accent(this@MainActivity))
                    textSize = 15f
                }
            }
            dialog.show()
        }
        val configured = PinStore(this).configured()
        if (configured) {
            root.addView(Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
                text = "Connect Galaxy Island (paired build)"
                setTextColor(Appearance.text(this@MainActivity))
                isChecked = app.d2lock.bridge.IslandBridge.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, value ->
                    app.d2lock.bridge.IslandBridge.setEnabled(this@MainActivity, value)
                }
            })
        }
        addButton(root, if (configured) "Change D2 PIN" else "Create D2 PIN") {
            pinDialog = PinUi.show(this, setup = !configured, change = configured, success = {
                authorized = true
                setContentView(buildSettings())
            })
        }
        if (configured) {
            section(root, "UNLOCK METHOD")
            val methods = listOf("PIN", "Pattern")
            addChoice(root, "D2 unlock method", methods, if (Prefs.unlockMethod(this) == "pattern") 1 else 0) { selected ->
                if (selected == 0) {
                    Prefs.setUnlockMethod(this, "pin")
                } else if (PatternStore(this).configured()) {
                    Prefs.setUnlockMethod(this, "pattern")
                } else {
                    PatternUi.show(this, setup = true, success = {
                        Prefs.setUnlockMethod(this, "pattern")
                        setContentView(buildSettings())
                    })
                }
            }
            if (PatternStore(this).configured()) {
                addButton(root, "Replace D2 pattern") {
                    // Keep the existing PIN as the recovery method. A new pattern file is created after app-data reset;
                    // changing an existing pattern is intentionally deferred rather than weakening verification.
                    Toast.makeText(this, "To replace the pattern, switch to PIN first. Pattern reset support is coming next.", Toast.LENGTH_LONG).show()
                }
            }
            section(root, "PASSKEY MANAGER")
            val passkeys = runCatching { D2PasskeyStore(this).list() }.getOrDefault(emptyList())
            root.addView(TextView(this).apply {
                text = if (passkeys.isEmpty()) "No D2 passkeys saved yet." else "${passkeys.size} D2 passkey${if (passkeys.size == 1) "" else "s"} saved."
                textSize = 14f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(6), 0, dp(6), dp(8))
            })
            passkeys.forEach { record ->
                addButton(root, "${record.userName.ifBlank { record.displayName.ifBlank { "Passkey" } }} · ${record.rpId}") {
                    AlertDialog.Builder(this)
                        .setTitle(record.userName.ifBlank { "D2 passkey" })
                        .setMessage("Site / app: ${record.rpId}\nDisplay name: ${record.displayName.ifBlank { "Not provided" }}")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Delete") { _, _ ->
                            runCatching { D2PasskeyStore(this).delete(record.id) }
                            setContentView(buildSettings())
                        }
                        .show()
                }
            }
            addButton(root, "Credential provider settings") {
                val intents = listOf(
                    Intent("android.settings.CREDENTIAL_PROVIDER"),
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                )
                val target = intents.firstOrNull { it.resolveActivity(packageManager) != null }
                if (target != null) startActivity(target)
                else Toast.makeText(this, "Credential provider settings are unavailable on this build.", Toast.LENGTH_LONG).show()
            }
            root.addView(TextView(this).apply {
                text = "New passkeys are created when a compatible app or website asks Android Credential Manager to save one with D2."
                textSize = 13f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(6), 0, dp(6), dp(12))
            })

            root.addView(TextView(this).apply {
                text = "Pattern is independent of Samsung Keyguard. Your 6-digit D2 PIN remains available as the recovery unlock method."
                textSize = 13f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(6), 0, dp(6), dp(12))
            })
        }
        if (!configured) {
            root.addView(TextView(this).apply {
                text = "Create a D2 PIN first. D2 does not create, change, or dismiss a Samsung screen lock.\\n\\nThis is an app privacy screen: Home, Recents, force-stop, uninstall, root, and reboot can bypass it. It is not device encryption or a guarantee against D2 boot errors. If you forget the PIN, clearing D2 app data resets it and its settings."
                textSize = 16f
                setTextColor(Appearance.text(this@MainActivity))
            })
            return settingsScroll(root)
        }
        val taps = DoubleTap()
        addButton(root, "Double-tap to lock D2") {
            if (taps.tap(SystemClock.elapsedRealtime())) {
                startActivity(Intent(this, LockScreenActivity::class.java))
            }
        }
        section(root, "HOME SCREEN WIDGET")
        addButton(root, "Add customizable D2 widget") {
            val manager = getSystemService(AppWidgetManager::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, D2Widget::class.java), null, null)
            } else Toast.makeText(this, "On your home screen, open Widgets and add the Samsung Lock D2 widget.", Toast.LENGTH_LONG).show()
        }
        root.addView(TextView(this).apply {
            text = "Each widget has its own settings for style, tap action and label. Long-press a placed widget and choose its widget settings when your launcher supports reconfiguration."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(12))
        })

        val enabled = Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Show D2 when the screen wakes"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setEnabled(this@MainActivity, checked)
                if (checked) LockScreenService.start(this@MainActivity) else LockScreenService.stop(this@MainActivity)
            }
        }
        root.addView(enabled, rowParams())

        root.addView(android.view.View(this).apply {
            setBackgroundColor((0x22 shl 24) or (Appearance.text(this@MainActivity) and 0xffffff))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(10) })
        section(root, "SHIZUKU")
        root.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Use Shizuku in Samsung Lock D2"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.shizukuEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShizukuEnabled(this@MainActivity, checked)
                Toast.makeText(this@MainActivity, if (checked) "D2 Shizuku integration enabled" else "D2 Shizuku integration disabled", Toast.LENGTH_SHORT).show()
                refreshShizukuUi()
            }
        }, rowParams())
        root.addView(shizukuCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        section(root, "D2 SYSTEM HEALTH")
        root.addView(systemHealthCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        addButton(root, "Refresh system health") { refreshShizukuUi() }

        section(root, "LOCK-SCREEN QUICK SETTINGS")
        root.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Guard Quick Settings while D2 is locked"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.quickSettingsGuard(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setQuickSettingsGuard(this@MainActivity, checked) }
        }, rowParams())
        root.addView(TextView(this).apply {
            text = "Experimental Samsung/Android guard. D2 keeps the notification shade collapsed while its lock screen has focus. This does not modify SystemUI or disable emergency/recovery controls, and firmware behavior may vary."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(14))
        })

        val rootMode = Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Optional KernelSU root mode"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.rootMode(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !RootManager.isAvailable()) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "Root shell was not detected", Toast.LENGTH_LONG).show()
                } else Prefs.setRootMode(this@MainActivity, checked)
            }
        }
        root.addView(rootMode, rowParams())

        root.addView(Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Require D2 authentication to leave (root kiosk)"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.kiosk(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setKiosk(this@MainActivity, checked)
            }
        }, rowParams())
        root.addView(TextView(this).apply {
            text = "Experimental: grant D2 root access in KernelSU. When kiosk is active, Home and Recents are blocked until your selected D2 unlock method is accepted. Check for ‘Kiosk active’ on the lock screen. Preview stays unlocked.\\n\\nAndroid kiosk mode interacts with the system keyguard, but D2 never sets a Samsung PIN. A crash or unresponsive app releases kiosk after about 20 seconds; reboot is the fallback recovery. Power/reboot and root remain bypasses. Primary, unmanaged user only."
            textSize = 14f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(TextView(this).apply {
            text = "Phone calls during kiosk: the selected Phone app and system call screen are allowed. Grant notification access below for Answer/Decline controls on D2. Phone-app screens may be accessible without completing D2 authentication; other apps and Home/Recents remain restricted. Call controls do not display caller names or numbers on D2, even when message previews are hidden."
            textSize = 14f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(0, 0, 0, dp(16))
        })

        section(root, "ADB / USB RECOVERY")
        root.addView(TextView(this).apply {
            text = RootManager.usbAdbState()?.summary ?: "Root access is required to inspect Samsung USB/ADB state."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(10))
        })
        root.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "ADB / USB recovery mode"
            textSize = 17f
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.adbRecovery(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!RootManager.isAvailable()) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "KernelSU/root access is required", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                val result = if (checked) RootManager.enableUsbAdbRecovery() else RootManager.disableUsbAdbRecovery()
                if (result.first) Prefs.setAdbRecovery(this@MainActivity, checked) else isChecked = !checked
                Toast.makeText(this@MainActivity, result.second, Toast.LENGTH_LONG).show()
            }
        }, rowParams())
        root.addView(TextView(this).apply {
            text = "Root-only recovery option. When enabled, D2 requests block_usb_lock=0 and enables the ADB USB function. Samsung firmware can still override USB policy, and an untrusted computer still requires ADB authorization. Disable this option when recovery access is not needed."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(14))
        })

        section(root, "THEME & COLORS")
        ThemeOptions.add(this, root, ::refreshAppearance)
        section(root, "APP ICON")
        val iconOptions = IconManager.options
        val currentIcon = IconManager.selected(this)
        addChoice(
            root,
            "Titanium launcher icon",
            iconOptions.map { it.label },
            iconOptions.indexOfFirst { it.key == currentIcon }.coerceAtLeast(0)
        ) { selected ->
            IconManager.apply(this, iconOptions[selected].key)
            Toast.makeText(this, "${iconOptions[selected].label} applied", Toast.LENGTH_SHORT).show()
        }
        addButton(root, "Reset Titanium icon") {
            IconManager.reset(this)
            Toast.makeText(this, "Titanium Purple restored", Toast.LENGTH_SHORT).show()
        }
        root.addView(TextView(this).apply {
            text = "Choose the D2 Titanium launcher color. Some launchers may take a moment to refresh the icon."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(12))
        })

        section(root, "APP THEME")
        root.getChildAt(root.childCount - 1).tag = "app_theme"
        val appearanceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = Appearance.glass(this@MainActivity, 28f, 30, true) }
        root.addView(appearanceCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        appearanceCard.addView(TextView(this).apply {
            text = "Dim wallpaper: ${Prefs.wallpaperDim(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "wallpaper_dim_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 80
            progress = Prefs.wallpaperDim(this@MainActivity)
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setWallpaperDim(this@MainActivity, value)
                    (root.findViewWithTag<TextView>("wallpaper_dim_label"))?.text = "Dim wallpaper: $value%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        appearanceCard.addView(TextView(this).apply {
            text = "Lock-screen glass: ${Prefs.lockGlass(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "lock_glass_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 80
            progress = Prefs.lockGlass(this@MainActivity) - 20
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val glass = value + 20
                    Prefs.setLockGlass(this@MainActivity, glass)
                    root.findViewWithTag<TextView>("lock_glass_label")?.text = "Lock-screen glass: $glass%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Lock-screen UI scale: ${Prefs.lockScale(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "lock_scale_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 30
            progress = Prefs.lockScale(this@MainActivity) - 85
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = value + 85
                    Prefs.setLockScale(this@MainActivity, scale)
                    root.findViewWithTag<TextView>("lock_scale_label")?.text = "Lock-screen UI scale: $scale%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        section(appearanceCard, "CLOCK & WEATHER")
        appearanceCard.getChildAt(appearanceCard.childCount - 1).tag = "clock_weather"
        addChoice(appearanceCard, "Clock style", listOf("Adaptive Clean", "Classic", "Rounded", "Condensed", "Bold", "Minimal"),
            listOf("adaptive", "classic", "rounded", "condensed", "bold", "minimal").indexOf(Prefs.clockStyle(this)).coerceAtLeast(0)) {
            Prefs.setClockStyle(this, listOf("adaptive", "classic", "rounded", "condensed", "bold", "minimal")[it])
        }
        addChoice(appearanceCard, "Adaptive clock layout", listOf("Auto", "Single line", "Stacked"),
            listOf("auto", "single", "stacked").indexOf(Prefs.clockLayout(this)).coerceAtLeast(0)) {
            Prefs.setClockLayout(this, listOf("auto", "single", "stacked")[it])
        }
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Adaptive clock sizing"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.clockAdaptive(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setClockAdaptive(this@MainActivity, checked) }
        }, rowParams())
        appearanceCard.addView(TextView(this).apply {
            text = "Clock size: ${Prefs.clockScale(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "clock_scale_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 50
            progress = Prefs.clockScale(this@MainActivity) - 80
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = value + 80
                    Prefs.setClockScale(this@MainActivity, scale)
                    root.findViewWithTag<TextView>("clock_scale_label")?.text = "Clock size: $scale%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Show date under clock"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showDate(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowDate(this@MainActivity, checked) }
        }, rowParams())
        appearanceCard.addView(TextView(this).apply {
            text = "Weather & battery glass: ${Prefs.componentGlass(this@MainActivity, "top_info")}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "top_info_glass_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 80
            progress = Prefs.componentGlass(this@MainActivity, "top_info") - 20
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val glass = value + 20
                    Prefs.setComponentGlass(this@MainActivity, "top_info", glass)
                    root.findViewWithTag<TextView>("top_info_glass_label")?.text = "Weather & battery glass: $glass%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Media player glass: ${Prefs.componentGlass(this@MainActivity, "media")}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "media_glass_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 80
            progress = Prefs.componentGlass(this@MainActivity, "media") - 20
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val glass = value + 20
                    Prefs.setComponentGlass(this@MainActivity, "media", glass)
                    root.findViewWithTag<TextView>("media_glass_label")?.text = "Media player glass: $glass%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Weather & battery content size: ${Prefs.topInfoSize(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "top_info_size_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 40
            progress = Prefs.topInfoSize(this@MainActivity) - 80
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = value + 80
                    Prefs.setTopInfoSize(this@MainActivity, scale)
                    root.findViewWithTag<TextView>("top_info_size_label")?.text = "Weather & battery content size: $scale%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Show weather in pill"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showWeather(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowWeather(this@MainActivity, checked) }
        }, rowParams())
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Show battery percentage"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showBatteryPercent(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowBatteryPercent(this@MainActivity, checked) }
        }, rowParams())
        addChoice(appearanceCard, "Temperature unit", listOf("Fahrenheit", "Celsius"),
            if (Prefs.celsius(this)) 1 else 0) { Prefs.setCelsius(this, it == 1) }
        appearanceCard.addView(TextView(this).apply {
            text = "Primary weather location"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(8), 0, dp(4))
        })
        val weatherLocation = android.widget.EditText(this).apply {
            hint = "Automatic · or city / postcode / region"
            setText(Prefs.weatherLocation(this@MainActivity))
            setTextColor(Appearance.text(this@MainActivity))
            setHintTextColor(Appearance.secondary(this@MainActivity))
            background = Appearance.glass(this@MainActivity, 22f, 26, true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setSingleLine(true)
        }
        appearanceCard.addView(weatherLocation, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
        addButton(appearanceCard, "Save primary weather location") {
            Prefs.setWeatherLocation(this, weatherLocation.text.toString())
            Toast.makeText(this, if (weatherLocation.text.isNullOrBlank()) "Weather set to automatic location" else "Primary weather location saved", Toast.LENGTH_SHORT).show()
        }
        addButton(appearanceCard, "Test weather now") {
            Prefs.setWeatherLocation(this, weatherLocation.text.toString())
            Toast.makeText(this, "Checking weather…", Toast.LENGTH_SHORT).show()
            app.d2lock.weather.WeatherRepository.load(this) { weather ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        weather?.let { "${it.temperature}° · ${it.label}" } ?: "Weather unavailable. Check location permission or Primary Location.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        appearanceCard.addView(TextView(this).apply {
            text = "Leave Primary Location blank to use the device location. Enter a city, postcode, or city + country/region for global weather."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(10))
        })
        section(appearanceCard, "MEDIA")
        addChoice(appearanceCard, "Media player layout", listOf("Compact", "Comfortable", "Large"),
            listOf("compact", "comfortable", "large").indexOf(Prefs.mediaLayout(this)).coerceAtLeast(0)) {
            Prefs.setMediaLayout(this, listOf("compact", "comfortable", "large")[it])
        }
        appearanceCard.addView(TextView(this).apply {
            text = "Media button size: ${Prefs.mediaButtonsScale(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "media_button_scale_label"
        })
        appearanceCard.addView(SeekBar(this).apply {
            max = 40
            progress = Prefs.mediaButtonsScale(this@MainActivity) - 80
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = value + 80
                    Prefs.setMediaButtonsScale(this@MainActivity, scale)
                    root.findViewWithTag<TextView>("media_button_scale_label")?.text = "Media button size: $scale%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Show media player while D2 is locked"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showMedia(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowMedia(this@MainActivity, checked) }
        }, rowParams())
        addButton(appearanceCard, "Reset appearance to defaults") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reset appearance?")
                .setMessage("This resets visual customization only. PIN, pattern, kiosk, root, Shizuku, ADB recovery, and notification privacy are not changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    Prefs.resetAppearance(this)
                    refreshAppearance()
                }
                .show()
        }
        section(root, "NOTIFICATIONS")
        root.getChildAt(root.childCount - 1).tag = "notifications"
        val privacyCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = Appearance.glass(this@MainActivity, 28f, 30, true) }
        root.addView(privacyCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        privacyCard.addView(Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Live notification banners while D2 is locked"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.liveNotifications(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setLiveNotifications(this@MainActivity, checked) }
        }, rowParams())
        privacyCard.addView(TextView(this).apply {
            text = "New notifications appear briefly at the top of D2 and stay in the notification list. Banners follow the privacy choice below. Tap a notification and authenticate with D2 to open it. Your messaging app controls sound and vibration."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
        })
        addChoice(privacyCard, "When D2 is locked", listOf("Hide all", "Count only", "App names", "Public only", "All previews (private too)"),
            Prefs.notificationPrivacy(this)) { Prefs.setNotificationPrivacy(this, it) }
        addChoice(privacyCard, "Notification card density", listOf("Compact", "Comfortable", "Large"),
            listOf("compact", "comfortable", "large").indexOf(Prefs.notificationDensity(this)).coerceAtLeast(0)) {
            Prefs.setNotificationDensity(this, listOf("compact", "comfortable", "large")[it])
        }
        privacyCard.addView(TextView(this).apply {
            text = "Notification corner radius: ${Prefs.notificationRadius(this@MainActivity)}dp"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "notification_radius_label"
        })
        privacyCard.addView(SeekBar(this).apply {
            max = 24
            progress = Prefs.notificationRadius(this@MainActivity) - 16
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val radius = value + 16
                    Prefs.setNotificationRadius(this@MainActivity, radius)
                    root.findViewWithTag<TextView>("notification_radius_label")?.text = "Notification corner radius: ${radius}dp"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        privacyCard.addView(TextView(this).apply {
            text = "Notification glass: ${Prefs.notificationGlass(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "notification_glass_label"
        })
        privacyCard.addView(SeekBar(this).apply {
            max = 80
            progress = Prefs.notificationGlass(this@MainActivity) - 20
            progressTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val glass = value + 20
                    Prefs.setNotificationGlass(this@MainActivity, glass)
                    root.findViewWithTag<TextView>("notification_glass_label")?.text = "Notification glass: $glass%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        privacyCard.addView(TextView(this).apply {
            text = "Public only shows text from apps that mark it public. All previews can show private messages before you authenticate with D2. Apps marked secret stay hidden."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(8), 0, dp(8), dp(12))
        })
        section(root, "FLOATING BAR")
        root.getChildAt(root.childCount - 1).tag = "floating_bar"
        val floatingCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = Appearance.glass(this@MainActivity, 28f, 30, true) }
        root.addView(floatingCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val actions = listOf("None", "Camera", "Flashlight")
        for (side in listOf("left", "right")) {
            addChoice(floatingCard, "${side.replaceFirstChar { it.uppercase() }} action", actions,
                actions.indexOf(Prefs.shortcut(this, side)).coerceAtLeast(0)) {
                Prefs.setShortcut(this, side, actions[it])
            }
        }
        section(root, "MAIN · ACCESS & PREVIEW")
        root.getChildAt(root.childCount - 1).tag = "main_settings"
        addButton(root, "Grant notification and media access") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        addButton(root, "Grant optional feature permissions") { requestRuntimePermissions() }
        addButton(root, "Choose wallpaper") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, wallpaperPicker)
        }
        addButton(root, "Preview lock screen") {
            startActivity(Intent(this, LockScreenActivity::class.java).putExtra("preview", true))
        }
        root.addView(TextView(this).apply {
            text = "Double-tap the D2 button or its home-screen widget to open the PIN screen. Taps elsewhere on the home screen are controlled by your launcher.\\n\\nD2 uses its own PIN and does not turn the display off. Optional kiosk mode uses Android task restrictions and interacts with keyguard internally.\\n\\nWithout active kiosk, Home/Recents can bypass D2. Root, recovery, and reboot remain bypasses in either mode. D2 cannot repair firmware or guarantee prevention of download-mode errors."
            textSize = 14f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), dp(28), dp(4), dp(10))
        })
        return settingsScroll(root)
    }

    private fun settingsScroll(root: LinearLayout) = ScrollView(this).apply {
        addView(root)
        setBackgroundColor(Appearance.background(this@MainActivity))
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun refreshAppearance() {
        val host = findViewById<ViewGroup>(android.R.id.content)
        val y = (host.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        Appearance.apply(this)
        val settings = buildSettings()
        setContentView(settings)
        settings.post { (settings as? ScrollView)?.scrollTo(0, y) }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = "$label   ›"
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            isAllCaps = false
            textSize = 16f
            setTextColor(Appearance.text(this@MainActivity))
            background = Appearance.glass(this@MainActivity, 28f, 34, true)
            setOnClickListener { action() }
        }, rowParams())
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 12f; letterSpacing = .12f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(10), dp(22), 0, dp(10))
        })
    }
    private fun pillBackground() = Appearance.glass(this, 22f, if (Appearance.dark(this)) 38 else 62, true)

    private fun shizukuCard(): ViewGroup {
        val enabled = Prefs.shizukuEnabled(this)
        val running = enabled && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = enabled && running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return LinearLayout(this).apply {
            tag = "shizuku_card"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = Appearance.glass(this@MainActivity, 28f, 34, true)
            addView(TextView(this@MainActivity).apply {
                text = when {
                    !enabled -> "Shizuku disabled in D2"
                    granted -> "●  Shizuku connected"
                    running -> "Shizuku running · permission required"
                    else -> "Shizuku not running"
                }
                textSize = 17f
                setTextColor(if (granted) Color.rgb(102, 220, 132) else Appearance.text(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text = when {
                    !enabled -> "The Shizuku service may keep running, but Samsung Lock D2 will not use it."
                    granted -> "Service running · Authorized"
                    running -> "Tap below to authorize Samsung Lock D2."
                    else -> "Start Shizuku, then return here. D2 reconnects automatically."
                }
                textSize = 13f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0, dp(3), 0, dp(8))
            })
            addView(Button(this@MainActivity).apply {
                text = when { !enabled -> "Shizuku disabled   ›"; granted -> "Revoke / Reconnect Shizuku   ›"; running -> "Authorize Shizuku   ›"; else -> "Connect Shizuku   ›" }
                isEnabled = enabled
                isAllCaps = false
                setTextColor(Appearance.text(this@MainActivity))
                background = Appearance.glass(this@MainActivity, 22f, 30, true)
                setOnClickListener {
                    if (!Shizuku.pingBinder()) {
                        Toast.makeText(this@MainActivity, "Start Shizuku first, then return to D2.", Toast.LENGTH_LONG).show()
                    } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        Shizuku.requestPermission(shizukuRequestCode)
                    } else {
                        refreshShizukuUi()
                        Toast.makeText(this@MainActivity, "Shizuku is connected and authorized", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
    }

    private fun systemHealthCard(): ViewGroup {
        val rootReady = RootManager.isAvailable()
        val shizukuRunning = Prefs.shizukuEnabled(this) && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuRunning && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val notificationsReady = runCatching {
            getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
                ComponentName(this, app.d2lock.notifications.LockNotificationListener::class.java)
            )
        }.getOrDefault(false)
        val adbState = if (rootReady) RootManager.usbAdbState() else null
        val rows = listOf(
            "ROOT" to rootReady,
            "SHIZUKU SERVICE" to shizukuRunning,
            "SHIZUKU AUTHORIZED" to shizukuGranted,
            "NOTIFICATION ACCESS" to notificationsReady,
            "D2 WAKE SERVICE" to Prefs.enabled(this),
            "ADB RECOVERY" to (Prefs.adbRecovery(this) && adbState?.adbEnabled == "1"),
            "GALAXY ISLAND" to app.d2lock.bridge.IslandBridge.enabled(this)
        )
        val ready = rows.count { it.second }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = Appearance.glass(this@MainActivity, 28f, 34, true)
            addView(TextView(this@MainActivity).apply {
                text = "D2 Ready — $ready/${rows.size} checks active"
                textSize = 18f
                setTextColor(Appearance.text(this@MainActivity))
                setPadding(0, 0, 0, dp(8))
            })
            rows.forEach { (name, ok) ->
                addView(TextView(this@MainActivity).apply {
                    text = (if (ok) "✓  " else "○  ") + name
                    textSize = 14f
                    setTextColor(if (ok) Color.rgb(102, 220, 132) else Appearance.secondary(this@MainActivity))
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = "Status only. Optional features may remain off by choice; a hollow check does not necessarily indicate an error."
                textSize = 12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0, dp(8), 0, 0)
            })
        }
    }
    private fun refreshShizukuUi() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && authorized) {
                val host = findViewById<ViewGroup>(android.R.id.content)
                val scroll = host.getChildAt(0) as? ScrollView
                val y = scroll?.scrollY ?: 0
                val settings = buildSettings()
                setContentView(settings)
                settings.post { (settings as? ScrollView)?.scrollTo(0, y) }
            }
        }
    }
    private fun addChoice(parent: LinearLayout, title: String, choices: List<String>, selected: Int, save: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = Appearance.glass(this@MainActivity, 28f, 34, true)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Appearance.text(this@MainActivity))
        }
        val valueView = TextView(this).apply {
            text = choices[selected.coerceIn(0, choices.lastIndex)] + "   ›"
            textSize = 14f
            setTextColor(Appearance.accent(this@MainActivity))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(titleView)
        card.addView(valueView)
        card.setOnClickListener {
            val current = choices.indexOf(valueView.text.toString().substringBefore("   ›")).coerceAtLeast(0)
            val dialogContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(10))
            }
            choices.forEachIndexed { index, choice ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = Appearance.glass(this@MainActivity, 24f, if (index == current) 52 else 28, true)
                }
                val radio = RadioButton(this).apply {
                    isChecked = index == current
                    buttonTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity))
                    )
                    isClickable = false
                }
                val labels = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = choice
                        textSize = 17f
                        setTextColor(Appearance.text(this@MainActivity))
                    })
                    if (title == "D2 unlock method") addView(TextView(this@MainActivity).apply {
                        text = if (choice == "PIN") "Use your 6-digit D2 PIN" else "Draw your unlock pattern"
                        textSize = 13f
                        setTextColor(Appearance.secondary(this@MainActivity))
                    })
                }
                row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(54)))
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    valueView.text = choices[index] + "   ›"
                    save(index)
                    (dialogContent.tag as? AlertDialog)?.dismiss()
                }
                dialogContent.addView(row, LinearLayout.LayoutParams(-1, dp(if (title == "D2 unlock method") 72 else 62)).apply { bottomMargin = dp(8) })
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(dialogContent)
                .setNegativeButton("Cancel", null)
                .create()
            dialogContent.tag = dialog
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(this@MainActivity))
            }
            dialog.show()
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(8) })
    }
    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requestRuntimePermissions() {
        val missing = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.CAMERA
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 81)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == wallpaperPicker && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prefs.setWallpaper(this, uri.toString())
            Toast.makeText(this, "Wallpaper saved", Toast.LENGTH_SHORT).show()
        }
    }
}
