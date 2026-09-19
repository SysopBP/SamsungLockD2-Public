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
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.lockscreen.LockScreenService
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi
import app.d2lock.widget.DoubleTap
import app.d2lock.widget.D2Widget

class MainActivity : Activity() {
    private val wallpaperPicker = 701
    private var authorized = false
    private var pinDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PinUi.protect(this)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(TextView(this).apply { text = "Samsung Lock D2" })
    }

    override fun onResume() {
        super.onResume()
        if (!authorized && pinDialog?.isShowing != true) {
            if (PinStore(this).configured()) {
                pinDialog = PinUi.show(this, success = {
                    authorized = true
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
            setPadding(dp(24), dp(56), dp(24), dp(30))
            setBackgroundColor(Color.rgb(247, 247, 250))
        }
        root.addView(TextView(this).apply {
            text = "Samsung Lock D2"
            textSize = 32f
            setTextColor(Color.rgb(20, 20, 24))
        })
        root.addView(TextView(this).apply {
            text = "Independent 6-digit app PIN"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(24))
        })

        addButton(root, "Licenses and credits") {
            val notice = assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            val text = TextView(this).apply {
                this.text = "D2 Project — All rights reserved.\n\n$notice"
                setPadding(dp(20), dp(12), dp(20), dp(12))
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(this).setTitle("Licenses and credits")
                .setView(ScrollView(this).apply { addView(text) })
                .setPositiveButton("Close", null).show()
        }
        val configured = PinStore(this).configured()
        addButton(root, if (configured) "Change D2 PIN" else "Create D2 PIN") {
            pinDialog = PinUi.show(this, setup = !configured, change = configured, success = {
                authorized = true
                setContentView(buildSettings())
            })
        }
        if (!configured) {
            root.addView(TextView(this).apply {
                text = "Create a D2 PIN first. D2 does not create, change, or dismiss a Samsung screen lock.\n\nThis is an app privacy screen: Home, Recents, force-stop, uninstall, root, and reboot can bypass it. It is not device encryption or a guarantee against D2 boot errors. If you forget the PIN, clearing D2 app data resets it and its settings."
                textSize = 16f
            })
            return ScrollView(this).apply { addView(root) }
        }
        val taps = DoubleTap()
        addButton(root, "Double-tap to lock D2") {
            if (taps.tap(SystemClock.elapsedRealtime())) {
                startActivity(Intent(this, LockScreenActivity::class.java))
            }
        }
        addButton(root, "Add home-screen double-tap widget") {
            val manager = getSystemService(AppWidgetManager::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, D2Widget::class.java), null, null)
            } else Toast.makeText(this, "On your home screen, open Widgets and add D2 double-tap lock.", Toast.LENGTH_LONG).show()
        }

        val enabled = Switch(this).apply {
            text = "Show D2 when the screen wakes"
            textSize = 17f
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setEnabled(this@MainActivity, checked)
                if (checked) LockScreenService.start(this@MainActivity) else LockScreenService.stop(this@MainActivity)
            }
        }
        root.addView(enabled, rowParams())

        val rootMode = Switch(this).apply {
            text = "Optional KernelSU root mode"
            textSize = 17f
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
            text = "Require PIN to leave D2 (root kiosk)"
            textSize = 17f
            isChecked = Prefs.kiosk(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setKiosk(this@MainActivity, checked)
            }
        }, rowParams())
        root.addView(TextView(this).apply {
            text = "Experimental: grant D2 root access in KernelSU. When kiosk is active, Home and Recents are blocked until your D2 PIN is accepted. Check for ‘Kiosk active’ on the lock screen. Preview stays unlocked.\n\nAndroid kiosk mode interacts with the system keyguard, but D2 never sets a Samsung PIN. A crash or unresponsive app releases kiosk after about 20 seconds; reboot is the fallback recovery. Power/reboot and root remain bypasses. Primary, unmanaged user only."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        addButton(root, "Grant notification access") {
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
            text = "Double-tap the D2 button or its home-screen widget to open the PIN screen. Taps elsewhere on the home screen are controlled by your launcher.\n\nD2 uses its own PIN and does not turn the display off. Optional kiosk mode uses Android task restrictions and interacts with keyguard internally.\n\nWithout active kiosk, Home/Recents can bypass D2. Root, recovery, and reboot remain bypasses in either mode. D2 cannot repair firmware or guarantee prevention of download-mode errors."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(28), dp(4), dp(10))
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setOnClickListener { action() }
        }, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) }
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
