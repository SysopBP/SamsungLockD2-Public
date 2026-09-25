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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ImageView
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
    private val appWallpaperPicker = 702
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
        setContentView(TextView(this).apply { text = "Kiosk D2 Guardian" })
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
                    maybeShowWhatsNew()
                }, cancel = { finish() })
            } else {
                // Upgrades must not leave the old unprotected service enabled.
                Prefs.setEnabled(this, false)
                LockScreenService.stop(this)
                setContentView(buildSettings())
                maybeShowWhatsNew()
            }
        }
    }

    private fun maybeShowWhatsNew() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "current"
        }.getOrDefault("current")
        val prefs = getSharedPreferences("d2_whats_new", MODE_PRIVATE)
        if (prefs.getString("shown_version", "") == version) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "D2 $version"
                textSize = 13f
                setTextColor(Appearance.accent(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text = "New in this build"
                textSize = 22f
                setTextColor(Appearance.text(this@MainActivity))
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(TextView(this@MainActivity).apply {
                text = "• Floating glass settings navigation\n• Settings search\n• UI9 glass confirmation messages\n• Tap or slide Floating Bar unlock\n• Launcher icon preview and diagnostics\n• Settings backup and restore"
                textSize = 15f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(Appearance.text(this@MainActivity))
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("What's New")
            .setView(content)
            .setPositiveButton("Got it") { _, _ ->
                prefs.edit().putString("shown_version", version).apply()
            }
            .create()
        dialog.setOnCancelListener { prefs.edit().putString("shown_version", version).apply() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 32f, 82, true))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
        }
        dialog.show()
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
            setPadding(dp(22), dp(56), dp(22), dp(118))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(TextView(this).apply {
            text = "Kiosk D2 Guardian"
            textSize = 32f
            setTextColor(Appearance.text(this@MainActivity))
        })
        root.addView(TextView(this).apply {
            text = "Kiosk D2 Guardian Security Session · Independent app authentication"
            textSize = 15f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(0, dp(4), 0, dp(24))
        })

        val configured = PinStore(this).configured()
        if (!configured) {
            val setupCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = Appearance.glass(this@MainActivity, 30f, 42, true)
                addView(TextView(this@MainActivity).apply {
                    text = "Finish D2 setup"
                    textSize = 20f
                    setTextColor(Appearance.text(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Create your D2 PIN to unlock the settings tabs and protected features. Until this is completed, those sections are intentionally unavailable."
                    textSize = 14f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(0, dp(5), 0, dp(12))
                })
            }
            addButton(setupCard, "Create D2 PIN now") {
                pinDialog = PinUi.show(this, setup = true, success = {
                    authorized = true
                    setContentView(buildSettings())
                })
            }
            root.addView(setupCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }

        val settingsSearch = android.widget.EditText(this).apply {
            hint = if (configured) "Search settings" else "Finish D2 setup to search settings"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Appearance.text(this@MainActivity))
            setHintTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(18), 0, dp(18), 0)
            background = Appearance.glass(this@MainActivity, 28f, 34, true)
        }
        root.addView(settingsSearch, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(16) })

        section(root, "SETTINGS")
        root.getChildAt(root.childCount - 1).tag = "settings"
        val categories = if (configured) listOf(
            Triple("Main", "Security, access & preview", "main_settings"),
            Triple("Clock & Weather", "Clock style, global weather & location", "clock_weather"),
            Triple("Notifications", "Privacy, banners & card appearance", "notifications"),
            Triple("App Theme", "Glass, wallpaper, scale & media", "app_theme"),
            Triple("Floating Bar", "Left and right lock-screen actions", "floating_bar")
        ) else emptyList()
        settingsSearch.isEnabled = configured
        settingsSearch.alpha = if (configured) 1f else 0.55f
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
                        val rect = android.graphics.Rect()
                        view.getDrawingRect(rect)
                        root.offsetDescendantRectToMyCoords(view, rect)
                        (root.parent as? ScrollView)?.smoothScrollTo(0, (rect.top - dp(18)).coerceAtLeast(0))
                        view.postDelayed({ flashSettingsTarget(view) }, 280)
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
            settingsSearch.setOnEditorActionListener { _, _, _ ->
            val query = settingsSearch.text.toString().trim().lowercase()
            if (query.isNotEmpty()) {
                val target = when {
                    query.contains("weather") || query.contains("clock") -> "clock_weather"
                    query.contains("notification") || query.contains("privacy") -> "notifications"
                    query.contains("floating") || query.contains("slide") || query.contains("shortcut") -> "floating_bar"
                    query.contains("theme") || query.contains("glass") || query.contains("wallpaper") || query.contains("media") -> "app_theme"
                    else -> "main_settings"
                }
                root.findViewWithTag<android.view.View>(target)?.let { view ->
                    (root.parent as? ScrollView)?.smoothScrollTo(0, view.top)
                    view.postDelayed({ flashSettingsTarget(view) }, 280)
                }
            }
            true
        }
        return settingsHost(root)
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
            } else Toast.makeText(this, "On your home screen, open Widgets and add the Kiosk D2 Guardian widget.", Toast.LENGTH_LONG).show()
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
            text = "Use Shizuku in Kiosk D2 Guardian"
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
            val state = RootManager.usbAdbState()
            text = when {
                state == null -> "Root access is required to inspect Samsung USB/ADB state."
                Prefs.adbRecovery(this@MainActivity) && state.adbEnabled != "1" ->
                    "Recovery is configured · USB debugging is currently off · ${state.summary}"
                else -> state.summary
            }
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
        val iconPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Appearance.glass(this@MainActivity, 28f, 30, true)
        }
        val iconPreviewImage = ImageView(this).apply {
            setImageResource(IconManager.iconResource(currentIcon))
            contentDescription = "Selected D2 launcher icon"
        }
        val iconPreviewText = TextView(this).apply {
            text = iconOptions.firstOrNull { it.key == currentIcon }?.label ?: "Titanium Graphite"
            textSize = 16f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(14), 0, 0, 0)
        }
        iconPreview.addView(iconPreviewImage, LinearLayout.LayoutParams(dp(54), dp(54)))
        iconPreview.addView(iconPreviewText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(iconPreview, LinearLayout.LayoutParams(-1, dp(74)).apply { bottomMargin = dp(8) })
        val iconPicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        val iconCells = mutableMapOf<String, LinearLayout>()
        fun refreshIconPicker(selectedKey: String) {
            iconCells.forEach { (key, cell) ->
                cell.background = if (key == selectedKey)
                    Appearance.glass(this@MainActivity, 22f, 72, true) else null
                cell.alpha = if (key == selectedKey) 1f else .72f
            }
        }
        iconOptions.forEach { option ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = option.label + " D2 launcher icon"
                setPadding(dp(5), dp(6), dp(5), dp(5))
            }
            cell.addView(ImageView(this).apply {
                setImageResource(IconManager.iconResource(option.key))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            cell.addView(TextView(this).apply {
                text = option.label.substringAfterLast(" ")
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(Appearance.text(this@MainActivity))
            }, LinearLayout.LayoutParams(-1, -2))
            cell.setOnClickListener {
                IconManager.apply(this, option.key)
                iconPreviewImage.setImageResource(IconManager.iconResource(option.key))
                iconPreviewText.text = option.label
                refreshIconPicker(option.key)
                showD2Message(option.label + " applied")
            }
            iconCells[option.key] = cell
            iconPicker.addView(cell, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        refreshIconPicker(currentIcon)
        root.addView(iconPicker, LinearLayout.LayoutParams(-1, -2))

        addButton(root, "Reset Titanium icon") {
            IconManager.reset(this)
            showD2Message("Titanium Graphite restored")
        }
        addButton(root, "Icon diagnostics") {
            val diagnosticContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(10))
                addView(TextView(this@MainActivity).apply {
                    text = "Saved selection"
                    textSize = 13f
                    setTextColor(Appearance.secondary(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = IconManager.selected(this@MainActivity).replaceFirstChar { it.uppercase() }
                    textSize = 18f
                    setTextColor(Appearance.accent(this@MainActivity))
                    setPadding(0, dp(2), 0, dp(14))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "PACKAGE MANAGER STATES"
                    textSize = 12f
                    setTextColor(Appearance.secondary(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = IconManager.diagnostic(this@MainActivity)
                    textSize = 15f
                    setTextColor(Appearance.text(this@MainActivity))
                    setPadding(0, dp(5), 0, dp(12))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "0 = default   ·   1 = enabled   ·   2 = disabled"
                    textSize = 12f
                    setTextColor(Appearance.secondary(this@MainActivity))
                })
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Icon diagnostics")
                .setView(diagnosticContent)
                .setPositiveButton("OK", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
            }
            dialog.show()
        }
        root.addView(TextView(this).apply {
            text = "Choose the D2 Titanium launcher color. Samsung Theme Park or another custom icon pack can override D2’s selected launcher icon. If the preview changes but the Home screen icon does not, temporarily apply Samsung’s default icons and test again."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(12))
        })

        section(root, "APP THEME")
        root.getChildAt(root.childCount - 1).tag = "app_theme"
        val appearanceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = Appearance.glass(this@MainActivity, 28f, 30, true) }
        root.addView(appearanceCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        section(appearanceCard, "LOCK SCREEN EXTRAS")
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Wallpaper parallax"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.wallpaperParallax(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setWallpaperParallax(this@MainActivity, checked) }
        }, rowParams())
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Glass shimmer"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.glassShimmer(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setGlassShimmer(this@MainActivity, checked) }
        }, rowParams())
        appearanceCard.addView(Switch(this).apply {
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Double-tap empty lock screen to sleep"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.doubleTapSleep(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setDoubleTapSleep(this@MainActivity, checked) }
        }, rowParams())
        addChoice(appearanceCard, "Unlock haptics", listOf("Off", "Soft", "Medium", "Strong"),
            listOf("off", "soft", "medium", "strong").indexOf(Prefs.unlockHaptics(this)).coerceAtLeast(0)) {
            Prefs.setUnlockHaptics(this, listOf("off", "soft", "medium", "strong")[it])
        }
        appearanceCard.addView(TextView(this).apply {
            text = "Optional effects are off by default and work with any wallpaper."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(12))
        })

        appearanceCard.addView(TextView(this).apply {
            text = "Dim wallpaper: ${Prefs.wallpaperDim(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(4), 0, 0)
            tag = "wallpaper_dim_label"
        })
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(guardianSlider().apply {
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
                    // Apply the visual change live without rebuilding the activity/view hierarchy.
                    applyLiveGlassOpacity(root, glass)
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
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(guardianSlider().apply {
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
        appearanceCard.addView(Switch(this).apply {
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Appearance.accent(this@MainActivity), Appearance.secondary(this@MainActivity)))
            text = "Weather in Celsius (off: Fahrenheit)"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.celsius(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setCelsius(this@MainActivity, checked) }
        }, rowParams())
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
            showD2Message(if (weatherLocation.text.isNullOrBlank()) "Weather set to automatic location" else "Primary weather location saved")
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
        appearanceCard.addView(guardianSlider().apply {
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
        privacyCard.addView(guardianSlider().apply {
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
        privacyCard.addView(guardianSlider().apply {
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
        addChoice(floatingCard, "Unlock gesture", listOf("Tap or slide", "Slide only", "Tap only"),
            listOf("tap_or_slide", "slide_only", "tap_only").indexOf(Prefs.floatingUnlockGesture(this)).coerceAtLeast(0)) {
            Prefs.setFloatingUnlockGesture(this, listOf("tap_or_slide", "slide_only", "tap_only")[it])
        }
        floatingCard.addView(TextView(this).apply {
            text = "Slide left, right, or up on the center unlock control. The glass control follows your finger and snaps back if the gesture does not reach the unlock threshold."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(10))
        })
        val actions = listOf("None", "Camera", "Flashlight")
        for (side in listOf("left", "right")) {
            val current = Prefs.shortcut(this, side)
            val currentLabel = if (current.startsWith("app:")) {
                val pkg = current.removePrefix("app:")
                runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("Selected app")
            } else current
            addButton(floatingCard, "${side.replaceFirstChar { it.uppercase() }} shortcut · $currentLabel") {
                val builtIns = arrayOf("None", "Camera", "Flashlight", "Choose an app…")
                val dialog = AlertDialog.Builder(this)
                    .setTitle("${side.replaceFirstChar { it.uppercase() }} shortcut")
                    .setItems(builtIns) { _, which ->
                        if (which < 3) {
                            Prefs.setShortcut(this, side, builtIns[which])
                            showD2Message("${builtIns[which]} shortcut selected")
                            refreshAppearance()
                        } else showAppShortcutPicker(side)
                    }.setNegativeButton("Cancel", null).create()
                dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this, 30f, 76, true)) }
                dialog.show()
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
        section(root, "D2 APP BACKGROUND")
        addButton(root, "Choose D2 app wallpaper") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, appWallpaperPicker)
        }
        root.addView(TextView(this).apply {
            text = "D2 background dim: ${Prefs.appWallpaperDim(this@MainActivity)}%"
            textSize = 14f
            setTextColor(Appearance.secondary(this@MainActivity))
            tag = "d2_app_wallpaper_dim_label"
        })
        root.addView(guardianSlider().apply {
            max = 90
            progress = Prefs.appWallpaperDim(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setAppWallpaperDim(this@MainActivity, value)
                    root.findViewWithTag<TextView>("d2_app_wallpaper_dim_label")?.text = "D2 background dim: $value%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { refreshAppearance() }
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        addButton(root, "Reset D2 app wallpaper") {
            Prefs.clearAppWallpaper(this)
            showD2Message("D2 app wallpaper reset")
            refreshAppearance()
        }
        addButton(root, "Preview lock screen") {
            startActivity(Intent(this, LockScreenActivity::class.java).putExtra("preview", true))
        }
        section(root, "LOCK-SCREEN PROFILES")
        val profileLabels = listOf("Daily", "AMOLED", "Minimal", "Night")
        val profileKeys = listOf("daily", "amoled", "minimal", "night")
        val selectedProfile = profileKeys.indexOf(Prefs.activeProfile(this)).coerceAtLeast(0)
        addChoice(root, "Profile", profileLabels, selectedProfile) { index ->
            val value = profileKeys[index]
            Prefs.applyProfile(this, value)
            showD2Message("${profileLabels[index]} profile applied")
            refreshAppearance()
        }
        root.addView(TextView(this).apply {
            text = "Profiles instantly tune the clock, glass, wallpaper dimming, notifications, media and Floating Bar behavior. Individual changes remain available afterward."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })
        addButton(root, "Save current as custom profile") {
            val input = android.widget.EditText(this).apply {
                hint = "Profile name"
                setSingleLine()
                setTextColor(Appearance.text(this@MainActivity))
                setHintTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = Appearance.glass(this@MainActivity, 24f, 34, true)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Save custom profile")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isBlank()) showD2Message("Enter a profile name")
                    else {
                        Prefs.saveCustomProfile(this@MainActivity, name)
                        dialog.dismiss()
                        showD2Message("$name profile saved")
                        refreshAppearance()
                    }
                }
            }
            dialog.show()
        }
        val customProfiles = Prefs.customProfileNames(this)
        if (customProfiles.isNotEmpty()) {
            addButton(root, "Load custom profile") {
                val names = Prefs.customProfileNames(this)
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Custom profiles")
                    .setItems(names.toTypedArray()) { _, which ->
                        val name = names[which]
                        if (Prefs.applyCustomProfile(this, name)) {
                            showD2Message("$name profile applied")
                            refreshAppearance()
                        }
                    }.setNegativeButton("Cancel", null).create()
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                }
                dialog.show()
            }
            addButton(root, "Delete custom profile") {
                val names = Prefs.customProfileNames(this)
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Delete custom profile")
                    .setItems(names.toTypedArray()) { _, which ->
                        val name = names[which]
                        Prefs.deleteCustomProfile(this, name)
                        showD2Message("$name profile deleted")
                        refreshAppearance()
                    }.setNegativeButton("Cancel", null).create()
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                }
                dialog.show()
            }
        }

        section(root, "BACKUP & RESTORE")
        addButton(root, "Copy settings backup") {
            val backup = Prefs.exportSettings(this)
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("D2 settings backup", backup))
            showD2Message("D2 settings backup copied")
        }
        addButton(root, "Restore settings backup") {
            val input = android.widget.EditText(this).apply {
                hint = "Paste D2 settings backup"
                minLines = 5
                maxLines = 10
                setTextColor(Appearance.text(this@MainActivity))
                setHintTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = Appearance.glass(this@MainActivity, 24f, 34, true)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Restore D2 settings")
                .setMessage("Appearance and lock-screen preferences will be restored. Root, kiosk, ADB recovery, Shizuku state, and D2 enabled state are intentionally not imported.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    runCatching { Prefs.importSettings(this@MainActivity, input.text.toString()) }
                        .onSuccess { count ->
                            dialog.dismiss()
                            showD2Message("Restored $count D2 settings")
                            refreshAppearance()
                        }
                        .onFailure { showD2Message("Backup is not valid D2 settings") }
                }
            }
            dialog.show()
        }
        root.addView(TextView(this).apply {
            text = "Double-tap the D2 button or its home-screen widget to open the PIN screen. Taps elsewhere on the home screen are controlled by your launcher.\\n\\nD2 uses its own PIN and does not turn the display off. Optional kiosk mode uses Android task restrictions and interacts with keyguard internally.\\n\\nWithout active kiosk, Home/Recents can bypass D2. Root, recovery, and reboot remain bypasses in either mode. D2 cannot repair firmware or guarantee prevention of download-mode errors."
            textSize = 14f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), dp(28), dp(4), dp(10))
        })
        return settingsHost(root)
    }

    private fun showAppShortcutPicker(side: String) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(labels) { _, which ->
                val info = apps[which]
                Prefs.setShortcut(this, side, "app:${info.activityInfo.packageName}")
                showD2Message("${info.loadLabel(packageManager)} shortcut selected")
                refreshAppearance()
            }
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this, 30f, 76, true)) }
        dialog.show()
    }

    private inner class GuardianSlider(context: android.content.Context) : android.view.View(context) {
        var max: Int = 100
            set(value) { field = value.coerceAtLeast(1); progress = progress.coerceAtMost(field); invalidate() }
        var progress: Int = 0
            set(value) { field = value.coerceIn(0, max); invalidate() }

        // Compatibility properties let existing settings blocks migrate without carrying
        // Android's SeekBar renderer into the new control.
        var progressTintList: android.content.res.ColorStateList? = null
        var thumbTintList: android.content.res.ColorStateList? = null
        private var listener: SeekBar.OnSeekBarChangeListener? = null
        // Some existing listeners are typed to SeekBar and may dereference the callback argument.
        // Keep a detached compatibility instance so the custom View never sends a null SeekBar.
        private val compatSeekBar: SeekBar by lazy { SeekBar(this@MainActivity).apply { max = this@GuardianSlider.max } }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val glassPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        fun setOnSeekBarChangeListener(value: SeekBar.OnSeekBarChangeListener?) { listener = value }

        init {
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
            val fraction = (progress.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
            val thumbX = left + (right - left) * fraction

            // Glass capsule: translucent body, soft highlight and subtle outline.
            glassPaint.color = if (Appearance.dark(this@MainActivity)) 0x4dffffff else 0x3dffffff
            canvas.drawRoundRect(android.graphics.RectF(left, cy - trackH / 2f, right, cy + trackH / 2f), radius, radius, glassPaint)
            ring.color = if (Appearance.dark(this@MainActivity)) 0x66ffffff else 0x55000000
            canvas.drawRoundRect(android.graphics.RectF(left, cy - trackH / 2f, right, cy + trackH / 2f), radius, radius, ring)
            glassPaint.color = 0x38ffffff
            canvas.drawRoundRect(android.graphics.RectF(left + dp(2), cy - trackH / 2f + dp(2), right - dp(2), cy - dp(1)), radius, radius, glassPaint)

            paint.color = (Appearance.accent(this@MainActivity) and 0x00ffffff) or 0x99000000.toInt()
            if (thumbX > left) {
                canvas.drawRoundRect(android.graphics.RectF(left, cy - trackH / 2f, thumbX, cy + trackH / 2f), radius, radius, paint)
            }

            // Elevated-looking thumb: outer contrast ring + accent center + small highlight.
            ring.color = if (Appearance.dark(this@MainActivity)) 0xddffffff.toInt() else 0xcc000000.toInt()
            canvas.drawCircle(thumbX, cy, dp(15).toFloat(), paint)
            canvas.drawCircle(thumbX, cy, dp(15).toFloat(), ring)
            paint.color = 0x55ffffff
            canvas.drawCircle(thumbX - dp(4), cy - dp(4), dp(4).toFloat(), paint)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (!isEnabled) return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Custom view has no framework SeekBar instance; do not pass null into callbacks.
                    updateFromTouch(event.x, true)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    updateFromTouch(event.x, true)
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    updateFromTouch(event.x, true)
                    // Tracking completion is handled locally; avoid null SeekBar callbacks.
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Tracking completion is handled locally; avoid null SeekBar callbacks.
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun updateFromTouch(x: Float, fromUser: Boolean) {
            val left = dp(10).toFloat()
            val widthAvailable = (width - dp(20)).coerceAtLeast(1)
            val fraction = ((x - left) / widthAvailable).coerceIn(0f, 1f)
            val value = kotlin.math.round(fraction * max).toInt()
            if (value != progress) {
                progress = value
                listener?.onProgressChanged(compatSeekBar, value, fromUser)
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun guardianSlider(): GuardianSlider = GuardianSlider(this)

    private fun showQuickSettingsSheet(root: LinearLayout, scroll: ScrollView) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = Appearance.glass(this@MainActivity, 34f, 86, true)
        }
        panel.addView(TextView(this).apply {
            text = "Quick Settings"
            textSize = 22f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(4), dp(2), dp(4), dp(10))
        })
        panel.addView(TextView(this).apply {
            text = "Jump straight to the D2 controls you use most."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        val dialog = AlertDialog.Builder(this).setView(panel).create()
        val shortcuts = listOf(
            "Lock-screen profiles" to "main_settings",
            "Clock & Weather" to "clock_weather",
            "Notifications" to "notifications",
            "Theme & Glass" to "app_theme",
            "Floating Bar & Apps" to "floating_bar"
        )
        shortcuts.forEach { (label, target) ->
            addButton(panel, label) {
                dialog.dismiss()
                root.findViewWithTag<android.view.View>(target)?.let { view ->
                    scroll.post {
                        scroll.smoothScrollTo(0, (view.top - dp(16)).coerceAtLeast(0))
                        view.postDelayed({ flashSettingsTarget(view) }, 280)
                    }
                }
            }
        }
        addButton(panel, "Full Settings") {
            dialog.dismiss()
            root.findViewWithTag<android.view.View>("settings")?.let { view ->
                scroll.post { scroll.smoothScrollTo(0, (view.top - dp(16)).coerceAtLeast(0)) }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setDimAmount(0.30f)
                attributes = attributes.apply { gravity = Gravity.BOTTOM }
            }
            panel.translationY = dp(40).toFloat()
            panel.alpha = 0f
            panel.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
        dialog.show()
    }

    private fun flashSettingsTarget(view: android.view.View) {
        val originalAlpha = view.alpha
        view.animate().cancel()
        view.animate().alpha(0.42f).setDuration(110).withEndAction {
            view.animate().alpha(1f).setDuration(180).withEndAction {
                view.animate().alpha(0.58f).setDuration(110).withEndAction {
                    view.animate().alpha(originalAlpha).setDuration(220).start()
                }.start()
            }.start()
        }.start()
    }

    private fun settingsHost(root: LinearLayout): ViewGroup {
        val host = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Appearance.background(this@MainActivity))
        }
        Prefs.appWallpaper(this)?.let { saved ->
            host.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 1f
                runCatching { setImageURI(Uri.parse(saved)) }
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            val dim = Prefs.appWallpaperDim(this)
            if (dim > 0) host.addView(android.view.View(this).apply {
                setBackgroundColor(Color.argb((255f * dim / 100f).toInt(), 0, 0, 0))
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val scroll = ScrollView(this).apply { tag = "settings_scroll"; setBackgroundColor(Color.TRANSPARENT); addView(root) }
        host.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = Appearance.glass(this@MainActivity, 36f, 82, true)
            elevation = dp(24).toFloat()
        }
        val destinations = listOf(
            Triple("⌂", "Main", "main_settings"),
            Triple("◷", "Clock", "clock_weather"),
            Triple("▣", "Alerts", "notifications"),
            Triple("✦", "Theme", "app_theme"),
            Triple("▬", "Bar", "floating_bar"),
            Triple("⚙", "Settings", "settings")
        )
        val items = mutableListOf<LinearLayout>()
        fun select(item: LinearLayout) {
            items.forEach { candidate ->
                candidate.animate().cancel()
                candidate.background = null
                candidate.scaleX = 1f
                candidate.scaleY = 1f
                (candidate.getChildAt(0) as? TextView)?.setTextColor(Appearance.secondary(this@MainActivity))
            }
            item.background = Appearance.glass(this@MainActivity, 28f, 48, true)
            (item.getChildAt(0) as? TextView)?.setTextColor(Appearance.accent(this@MainActivity))
            item.scaleX = .88f
            item.scaleY = .88f
            item.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        destinations.forEachIndexed { index, (icon, label, target) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                addView(TextView(this@MainActivity).apply {
                    text = icon
                    textSize = if (label == "Bar") 17f else 21f
                    gravity = Gravity.CENTER
                    setTextColor(if (index == 0) Appearance.accent(this@MainActivity) else Appearance.secondary(this@MainActivity))
                }, LinearLayout.LayoutParams(-1, dp(29)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Appearance.secondary(this@MainActivity))
                }, LinearLayout.LayoutParams(-1, dp(18)))
                setOnClickListener {
                    if (target == "settings") {
                        showQuickSettingsSheet(root, scroll)
                    } else {
                        root.findViewWithTag<android.view.View>(target)?.let { view ->
                            val rect = android.graphics.Rect()
                            view.getDrawingRect(rect)
                            root.offsetDescendantRectToMyCoords(view, rect)
                            scroll.smoothScrollTo(0, (rect.top - dp(18)).coerceAtLeast(0))
                            view.postDelayed({ flashSettingsTarget(view) }, 280)
                        }
                    }
                    select(this)
                }
            }
            items += item
            nav.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (index > 0) marginStart = dp(2)
            })
        }
        items.firstOrNull()?.background = Appearance.glass(this, 28f, 48, true)
        host.addView(nav, android.widget.FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(18), 0, dp(18), dp(18))
        })
        host.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            (nav.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = dp(18) + bars.bottom
                nav.layoutParams = params
            }
            insets
        }
        return host
    }

    private fun applyLiveGlassOpacity(root: ViewGroup, glass: Int) {
        // Update only the visible settings glass surfaces. Rebuilding the entire hierarchy
        // while a slider is tracking caused Samsung FrameLayout measurement instability.
        fun walk(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            when (view.tag) {
                "appearance_card", "lock_preview_card" ->
                    view.background = Appearance.glass(this, 28f, glass.coerceIn(20, 100), true)
            }
        }
        walk(root)
        root.invalidate()
    }

    private fun refreshAppearance() {
        val host = findViewById<ViewGroup>(android.R.id.content)
        val y = (host.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        Appearance.apply(this)
        val settings = buildSettings()
        setContentView(settings)
        settings.post { (settings as? ScrollView)?.scrollTo(0, y) }
    }

    private fun showD2Message(message: String, duration: Long = 2200L) {
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return
        host.findViewWithTag<android.view.View>("d2_glass_message")?.let { host.removeView(it) }
        val bubble = TextView(this).apply {
            tag = "d2_glass_message"
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = Appearance.glass(this@MainActivity, 26f, 72, true)
            elevation = dp(22).toFloat()
            alpha = 0f
            translationY = dp(16).toFloat()
        }
        host.addView(bubble, ViewGroup.LayoutParams(-1, -2))
        bubble.post {
            val params = bubble.layoutParams
            if (params is android.widget.FrameLayout.LayoutParams) {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.setMargins(dp(24), 0, dp(24), dp(30))
                bubble.layoutParams = params
            }
            bubble.animate().alpha(1f).translationY(0f).setDuration(180).start()
            bubble.postDelayed({
                bubble.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(160)
                    .withEndAction { runCatching { host.removeView(bubble) } }.start()
            }, duration)
        }
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
                    !enabled -> "The Shizuku service may keep running, but Kiosk D2 Guardian will not use it."
                    granted -> "Service running · Authorized"
                    running -> "Tap below to authorize Kiosk D2 Guardian."
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
                        showD2Message("Shizuku is connected and authorized")
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
            // Recovery health is separate from the current USB-debugging toggle.
            "ADB RECOVERY" to (Prefs.adbRecovery(this) && rootReady && adbState != null),
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
            showD2Message("Wallpaper saved")
        }
        if (requestCode == appWallpaperPicker && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prefs.setAppWallpaper(this, uri.toString())
            showD2Message("App background saved")
            refreshAppearance()
        }
    }
}
