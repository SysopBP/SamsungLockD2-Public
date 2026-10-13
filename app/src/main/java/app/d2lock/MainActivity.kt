package app.d2lock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
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
import android.view.View
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
import app.d2lock.lockscreen.KeyguardSignalReceiver
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
    private var gestureBackCallback: android.window.OnBackInvokedCallback? = null
    private var dockDestination = "home"
    private val pageScrollPositions = mutableMapOf<String, Int>()
    private val navigationHistory = java.util.ArrayDeque<String>()
    private var navigatingBack = false
    private var pageNavigator: ((String, View?) -> Unit)? = null
    private val sectionTitles = java.util.IdentityHashMap<View, String>()
    private var readinessDialog: AlertDialog? = null
    private var settingsScrollY = 0
    private var authorized = false
    private var appStopped = true
    private var appFingerprintAttempted = false
    private var appBiometricCancel: CancellationSignal? = null
    private val appBiometricSession = app.d2lock.security.BiometricSessionGate()
    private var pinDialog: AlertDialog? = null
    private val shizukuRequestCode = 910
    private var setupIntegrationStepPending = false
    private var setupSecurityStepPending = false
    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuUi() }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener { refreshShizukuUi() }
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == shizukuRequestCode) refreshShizukuUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Appearance.dark(this)) R.style.Theme_D2_Dark else R.style.Theme_SamsungLock)
        super.onCreate(savedInstanceState)
        dockDestination=savedInstanceState?.getString("settings_route") ?: "home"
        savedInstanceState?.getBundle("settings_positions")?.let { positions -> positions.keySet().forEach { key -> pageScrollPositions[key]=positions.getInt(key) } }
        savedInstanceState?.getStringArrayList("settings_history")?.forEach { navigationHistory.addLast(it) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gestureBackCallback = android.window.OnBackInvokedCallback { handleSettingsBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, gestureBackCallback!!)
        }
        Appearance.apply(this)
        PinUi.protect(this)
        Prefs.syncBootPolicy(this)
        RootManager.refreshAsync()
        app.d2lock.root.DmcRecovery.request(this, "app_open")
        window.statusBarColor = Color.TRANSPARENT
        setContentView(buildLockedBackdrop())
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
    }

    override fun onResume() {
        super.onResume()
        appStopped = false
        app.d2lock.root.DmcRecovery.request(this, "app_resume")
        if (setupSecurityStepPending) {
            setupSecurityStepPending = false
            window.decorView.post { showSetupWizard(2) }
            return
        }
        if (setupIntegrationStepPending) {
            setupIntegrationStepPending = false
            window.decorView.post { showSetupWizard(4) }
            return
        }
        if (!authorized && pinDialog?.isShowing != true && appBiometricCancel == null) {
            if (PinStore(this).configured()) {
                if (Prefs.unlockMethod(this) == "fingerprint" && !appFingerprintAttempted) {
                    authenticateAppWithFingerprint()
                } else showAppPinLogin()
            } else {
                // Upgrades must not leave the old unprotected service enabled.
                Prefs.setEnabled(this, false)
                LockScreenService.stop(this)
                setContentView(buildSettings())
                maybeShowWhatsNew()
            }
        }
    }

    private fun completeAppLogin() {
        if (appStopped || isFinishing || isDestroyed) return
        authorized = true
        app.d2lock.bridge.IslandBridge.setLocked(this, false)
        if (Prefs.enabled(this)) runCatching { LockScreenService.start(this) }
        setContentView(buildSettings())
        if (!Prefs.setupWizardSeen(this)) showSetupWizard() else maybeShowWhatsNew()
    }

    private fun showAppPinLogin() {
        if (appStopped || authorized || isFinishing || isDestroyed || pinDialog?.isShowing == true) return
        pinDialog = PinUi.show(this, success = { completeAppLogin() }, cancel = { finish() })
    }

    private fun authenticateAppWithFingerprint() {
        if (appStopped || authorized || isFinishing || isDestroyed || appBiometricCancel != null) return
        appFingerprintAttempted = true
        val manager = getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            showAppPinLogin()
            return
        }
        val token = appBiometricSession.begin()
        val cancel = CancellationSignal()
        appBiometricCancel = cancel
        fun fallback() {
            if (!appBiometricSession.consume(token)) return
            appBiometricCancel = null
            showAppPinLogin()
        }
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Unlock D2")
                .setSubtitle("Login with fingerprint")
                .setDescription("Authenticate to open the D2 app and protected settings, or use your D2 PIN.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Use D2 PIN", mainExecutor) { _, _ -> fallback() }
                .build()
            prompt.authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!appBiometricSession.consume(token)) return
                    appBiometricCancel = null
                    Log.i("D2AppAuth", "D2_APP_BIOMETRIC_SUCCESS type=${result.authenticationType}")
                    KeyguardSignalReceiver.recordFingerprintSuccess(this@MainActivity, "D2 app login")
                    completeAppLogin()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.i("D2AppAuth", "D2_APP_BIOMETRIC_ERROR code=$errorCode")
                    if (!appStopped && !isFinishing && !isDestroyed && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                        Toast.makeText(this@MainActivity, "$errString · Use D2 PIN", Toast.LENGTH_LONG).show()
                    }
                    fallback()
                }
                override fun onAuthenticationFailed() {
                    // A non-match leaves the system prompt active; it never authorizes settings.
                    Log.i("D2AppAuth", "D2_APP_BIOMETRIC_NON_MATCH")
                }
            })
        } catch (error: Throwable) {
            Log.w("D2AppAuth", "D2_APP_BIOMETRIC_START_FAILED", error)
            fallback()
        }
    }

    private fun hasNotificationAccess(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(this, app.d2lock.notifications.LockNotificationListener::class.java)
        )
    }.getOrDefault(false)

    private fun openNotificationAccessSettings() {
        setupIntegrationStepPending = true
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            setupIntegrationStepPending = false
            Toast.makeText(this, "Open Settings > Notifications > Notification access and enable Kiosk D2 Boot Guardian.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSetupWizard(step: Int = 0) {
        Prefs.setSetupWizardSeen(this, true)
        val titles = listOf("Welcome", "D2 authentication", "Samsung lock-screen compatibility", "Recovery readiness", "System integrations", "Lock behavior", "Test before kiosk", "Ready")
        val messages = listOf(
            "Kiosk D2 Boot Guardian can become your primary rooted lock surface. Complete and test D2 authentication and your recovery path before enabling full kiosk protection.",
            if (PinStore(this).configured()) "D2 authentication is configured. You can continue." else "Create a D2 PIN before full Guardian protection can be enabled. Pattern can be selected later in Security Center.",
            "For fingerprint login, first configure a Samsung PIN and enroll your fingerprint. Keep both configured, then open Samsung Settings → Fingerprints and turn Fingerprint unlock OFF. D2 uses the enrolled fingerprint through Android BiometricPrompt. Samsung may still require its own PIN after reboot or when strong authentication is required; complete that screen before using D2 fingerprint login. Your separate 6-digit D2 PIN remains the recovery method.",
            buildString {
                append(if (RootManager.isAvailable()) "✓ KernelSU Superuser granted to D2" else "○ KernelSU Superuser required")
                append("\n")
                append(if (Prefs.adbRecovery(this@MainActivity)) "✓ ADB / USB recovery configured" else "○ ADB / USB recovery is optional and currently off")
                append("\n\nD2's tested rooted configuration requires KernelSU Superuser. Grant Kiosk D2 Boot Guardian root access in KernelSU before continuing to full kiosk protection.")
            },
            buildString {
                val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val shizukuGranted = shizukuRunning && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                append(if (shizukuGranted) "✓ Shizuku connected and authorized" else if (shizukuRunning) "○ Shizuku permission required" else "○ Shizuku required — start the service")
                append("\n")
                append(if (hasNotificationAccess()) "✓ Notification Access enabled — media + lock notifications ready" else "○ Notification Access required — media player and lock notifications need it")
                append("\n")
                append(if (Prefs.xposedMaster(this@MainActivity)) "✓ LSPosed/Xposed integration configured" else "○ Xposed integration optional")
                append("\n")
                append(if (app.d2lock.bridge.IslandBridge.enabled(this@MainActivity)) "✓ Galaxy Island paired" else "○ Galaxy Island pairing optional")
                append("\n\nRequired for full D2 operation: KernelSU Superuser + Shizuku authorized + Notification Access. Fingerprint login requires an enrolled strong biometric; keep your D2 PIN available for recovery.")
            },
            "Recommended starting point: enable Show D2 when the screen wakes, keep Automatic fallback on, and verify call/relock behavior before enabling kiosk authentication.",
            "Use Test Guardian to open the lock surface and successfully return with your D2 credential. Full kiosk protection stays unchanged until you explicitly enable it after this test.",
            "Setup review is complete. Guardian will not silently enable kiosk mode. When your authentication and recovery tests pass, enable full kiosk protection from Guardian & Kiosk."
        )
        val body = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(12))
            background=Appearance.glass(this@MainActivity,30f,42,true)
            addView(TextView(this@MainActivity).apply {
                text="SETUP  ${step+1} / ${titles.size}"; textSize=12f; letterSpacing=.08f
                setTextColor(Appearance.secondary(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text=titles[step]; textSize=22f; setTextColor(Appearance.text(this@MainActivity)); setPadding(0,dp(4),0,dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text=messages[step]; textSize=14f; setLineSpacing(0f,1.14f); setTextColor(Appearance.text(this@MainActivity))
            })
            if(step==2) {
                addView(TextView(this@MainActivity).apply {
                    text="Open Samsung lock settings"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                    setOnClickListener {
                        setupSecurityStepPending=true
                        runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }.onFailure {
                            setupSecurityStepPending=false
                            Toast.makeText(this@MainActivity,"Open Samsung Settings > Fingerprints and turn Fingerprint unlock OFF. Keep your Samsung PIN and fingerprint enrolled.",Toast.LENGTH_LONG).show()
                        }
                    }
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
                addView(TextView(this@MainActivity).apply {
                    text="Check again"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Appearance.accent(this@MainActivity))
                    setPadding(dp(12),dp(10),dp(12),dp(10)); setOnClickListener { showSetupWizard(2) }
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5) })
            }
            if(step==1 && !PinStore(this@MainActivity).configured()) addView(TextView(this@MainActivity).apply {
                text="Create D2 PIN"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                setOnClickListener { PinUi.show(this@MainActivity,setup=true,success={ showSetupWizard(2) }) }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            if(step==3 && !RootManager.isAvailable()) addView(TextView(this@MainActivity).apply {
                text="Recheck KernelSU Superuser"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                setOnClickListener { showSetupWizard(3) }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            if(step==4) {
                val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val shizukuGranted = shizukuRunning && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                if(!shizukuGranted) addView(TextView(this@MainActivity).apply {
                    text=if(shizukuRunning) "Authorize Shizuku" else "Recheck Shizuku"
                    textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                    setOnClickListener {
                        if(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                            if(runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(shizukuRequestCode)
                            } else showSetupWizard(4)
                        } else {
                            Toast.makeText(this@MainActivity,"Start Shizuku, then return to D2.",Toast.LENGTH_LONG).show()
                        }
                    }
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            }
            if(step==3 && !hasNotificationAccess()) addView(TextView(this@MainActivity).apply {
                text="Enable Notification Access"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                setOnClickListener { openNotificationAccessSettings() }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            if(step==4) addView(guardianSwitch().apply {
                text="Show D2 when the screen wakes"; setTextColor(Appearance.text(this@MainActivity)); isChecked=Prefs.enabled(this@MainActivity)
                setOnCheckedChangeListener { _,checked ->
                    Prefs.setEnabled(this@MainActivity,checked)
                    if(checked) LockScreenService.start(this@MainActivity) else LockScreenService.stop(this@MainActivity)
                }
            },rowParams())
            if(step==5) addView(TextView(this@MainActivity).apply {
                text="Test Guardian"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,30,true); setPadding(dp(12),dp(11),dp(12),dp(11))
                setOnClickListener { startActivity(Intent(this@MainActivity,LockScreenActivity::class.java)) }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        }
        val builder=AlertDialog.Builder(this).setView(body)
        if(step>0) builder.setNegativeButton("Back") { _,_ -> showSetupWizard(step-1) } else builder.setNegativeButton("Later",null)
        if(step<titles.lastIndex) builder.setPositiveButton("Next") { _,_ ->
            if(step==1 && !PinStore(this).configured()) {
                showD2Message("Create and verify your D2 PIN before continuing")
                showSetupWizard(1)
            } else if(step==3 && !RootManager.isAvailable()) {
                showD2Message("Grant Kiosk D2 Boot Guardian Superuser access in KernelSU, then recheck")
                showSetupWizard(3)
            } else if(step==4) {
                val running=runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val granted=running && runCatching { Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                if(!granted) {
                    showD2Message(if(running) "Authorize Kiosk D2 Boot Guardian in Shizuku before continuing" else "Start Shizuku before continuing")
                    showSetupWizard(4)
                } else if(!hasNotificationAccess()) {
                    showD2Message("Enable Notification Access for D2 media controls and lock-screen notifications")
                    showSetupWizard(4)
                } else showSetupWizard(step+1)
            } else showSetupWizard(step+1)
        } else builder.setPositiveButton("Finish") { _,_ ->
            Prefs.setSetupWizardComplete(this,true)
            setContentView(buildSettings())
            showD2Message("Guardian setup review complete")
        }
        builder.create().also { dialog ->
            dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,34f,82,true)) }
            dialog.show()
        }
    }

    private fun maybeShowWhatsNew() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "current"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info?.longVersionCode?.toInt() ?: 0 else @Suppress("DEPRECATION") (info?.versionCode ?: 0)
        if (Prefs.introSeenVersion(this) == versionCode || Prefs.introDismissed(this)) return
        showWhatsNewNext(versionName, versionCode)
    }

    private fun showWhatsNewNext(versionName: String? = null, versionCode: Int? = null) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = Appearance.glass(this@MainActivity, 30f, 58, true)
        }
        val title = TextView(this).apply {
            text = "KIOSK D2 BOOT GUARDIAN"; textSize = 12f; letterSpacing = .08f
            setTextColor(Appearance.secondary(this@MainActivity)); setPadding(dp(4), 0, dp(4), dp(4))
        }
        val heading = TextView(this).apply {
            text = "What's New & Next"; textSize = 22f; setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        val content = TextView(this).apply {
            textSize = 14f; setTextColor(Appearance.text(this@MainActivity)); setLineSpacing(0f, 1.14f)
            setPadding(dp(16), dp(14), dp(16), dp(18)); background = Appearance.glass(this@MainActivity, 26f, 28, true)
        }
        fun whatsNew() {
            content.text = "WHAT'S NEW  •  OCTOBER 12, 2026  •  D2 ${versionName ?: "current"}\n\n🔓 FINGERPRINT APP LOGIN\n• Login with fingerprint now opens the D2 app and protected settings through Samsung’s secure biometric prompt\n• Use D2 PIN remains available; unavailable/error results fall back to PIN\n• Backgrounding revokes the session and rejects late biometric callbacks\n\n🧊 STANDARD GLASS DIALOGS\n• Rounded shared information and unlock-choice dialogs replace square platform panels\n• Fingerprint guidance now has numbered steps, an icon and Open settings / Got it buttons\n\n🧊 D2 POWER MENU\n• Adds confirmed soft restart and a rounded live D2 SRP status dialog to Samsung power controls\n• Requires LSPosed SystemUI plus System Framework scopes; preserves emergency and normal confirmation actions\n\n🛡 D2 SRP POWER-ACTION GUARD\n• Verifies fresh vault read-back before normal restart, power off and supported orderly soft restart\n• Cancels on failure or a 5-second timeout; late checks never trigger a delayed restart\n• Failed/unknown checks keep the phone on: open D2 SRP status, resolve the issue and retry\n• Status checks alone never shut down the phone; missing shutdown hooks may allow normal Samsung power actions without verification\n• Direct root zygote restarts can restart Android without an SRP check; other root apps, terminals and modules remain bypass paths\n• Recovery Center Soft restart uses the guarded System Server route; Restart SystemUI requires a fresh SRP check\n• Battery/thermal emergencies and direct root/hardware resets remain outside this guard\n\n🧩 D2 SRP — SAMSUNG RECOVERY PROTECTION\n• Runs inside Xposed System Server (UID 1000)\n• Targets: D2 SRP screen-lock and Maintenance Mode flags disabled; Download Mode AT-command flag enabled; derived Download Mode eligibility allowed\n• Validates the vault record and verifies read-back; unknown or failed checks stay unverified\n\n🔄 SECURITY SETUP DETECTION\n• Watches completed PIN/password/pattern changes and fingerprint/face additions or removals\n• Refreshes 2 seconds after setup changes and checks again at 12 seconds for delayed Samsung updates\n• Firmware detection still needs device testing; Android credentials and biometric enrollment stay separate from D2 SRP flags\n\n✅ LIVE FEATURE STATUS\n• Glass status dialog with icons, target checks, System Server identity and last-check time\n\n🚧 IN DEVELOPMENT\n• Additional framework controls and Galaxy Island Xposed integration remain disabled placeholders\n• Diagnostics, relock, Home/Recents, gestures, notification/media/call integration and related controls are planned for a later update\n• These switches do not control the current boot/wake observers, D2 SRP or power-menu hooks\n\n\n💤 RECENT HOTFIXES\n• Voicemail/call-state relock fix\n• Double-tap screen-off from empty space, with notification and call controls protected\n• Floating Bar visibility modes and coordinated dock/CORE/CHECK behavior\n\n🙏 D2 SRP DISCOVERY CREDIT\n• @diablOw — originally discovered the recovery behavior behind D2 SRP"
        }
        fun whatsNext() {
            val lock = if (PinStore(this@MainActivity).configured()) "✓" else "○"
            val wake = if (Prefs.enabled(this@MainActivity)) "✓" else "○"
            val kiosk = if (Prefs.kiosk(this@MainActivity)) "✓" else "○"
            val shizuku = if (Prefs.shizukuEnabled(this@MainActivity)) "✓" else "○"
            val root = if (Prefs.rootMode(this@MainActivity)) "✓" else "○"
            val xp = if (Prefs.xposedMaster(this@MainActivity)) "✓" else "○"
            content.text = "WHAT'S NEXT\n\n$lock Set up D2 authentication\n$wake Enable lock on wake\n$kiosk Configure kiosk protection\n$shizuku Connect Shizuku\n$root Enable KernelSU Root Mode when using the verified rooted configuration\n$xp LSPosed/System Framework + SystemUI integration enabled\n✓ Five consecutive reboots validated with the minimal systemReady handoff\n○ Complete D2-only fingerprint integration (not fully integrated yet)\n○ Remove the remaining Samsung biometric/keyguard presentation dependency\n○ Verify paired Galaxy Island glass and pop-out behavior\n○ Customize your lock screen\n○ Test your recovery path"
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
            background = Appearance.glass(this@MainActivity, 22f, 30, true); setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { action() }
        }
        tabs.addView(tab("What's New") { whatsNew() }, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
        tabs.addView(tab("What's Next") { whatsNext() }, LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
        body.addView(title); body.addView(heading); body.addView(tabs, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) }); body.addView(content)
        whatsNew()
        val dialog = AlertDialog.Builder(this).setView(body)
            .setNeutralButton("Don't show again") { _, _ -> Prefs.setIntroDismissed(this, true) }
            .setNegativeButton("Later", null)
            .setPositiveButton("Done") { _, _ -> if (versionCode != null) Prefs.setIntroSeenVersion(this, versionCode) }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 34f, 82, true))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Appearance.secondary(this@MainActivity))
        }
        dialog.show()
    }

    private fun showDmcStatus() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = Appearance.glass(this@MainActivity, 30f, 76, true)
        }
        fun label(size: Float, secondary: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(if (secondary) Appearance.secondary(this@MainActivity) else Appearance.text(this@MainActivity))
            setLineSpacing(0f, 1.12f)
        }
        body.addView(label(12f, true).apply { text = "D2  •  SYSTEM SERVER RECOVERY" })
        body.addView(label(22f).apply {
            text = "Feature status"
            setPadding(0, dp(6), 0, dp(12))
        })
        val summary = label(15f)
        body.addView(summary)
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(rows)
        val detail = label(13f, true)
        body.addView(detail)
        body.addView(label(12f, true).apply {
            text = "Samsung Recovery Protection (D2 SRP) checks the Samsung recovery vault. Status requires a successful vault read-back. Unknown means the current state could not be verified. Download Mode eligibility is calculated from the D2 SRP flags; the AT-command flag does not verify an AT-command response. Android credentials and D2 PIN/Pattern are separate."
            setPadding(0, dp(12), 0, dp(12))
        })
        body.addView(label(15f).apply {
            text = "Close"; gravity = Gravity.CENTER
            setTextColor(Appearance.accent(this@MainActivity))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = Appearance.glass(this@MainActivity, 22f, 30, true)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        fun render(status: app.d2lock.root.DmcRecovery.Status) {
            summary.text = if (status.targetsMet) "✅ All four targets verified" else "⚠ Targets not yet verified"
            rows.removeAllViews()
            fun row(icon: String, title: String, state: String, target: String, matches: Boolean) {
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = Appearance.glass(this@MainActivity, 20f, 28, true)
                }
                card.addView(label(21f).apply { text = icon; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(38), -2))
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(label(14f).apply { text = title })
                info.addView(label(12f, true).apply { text = "$state  •  Target: $target" })
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(label(18f).apply {
                    text = if (matches) "✅" else if (state == "Unknown") "○" else "⚠"
                    contentDescription = if (matches) "$title target verified" else "$title target not verified"
                    setPadding(dp(8), 0, 0, 0)
                })
                rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            fun flag(value: Int?) = when (value) { 0 -> "Disabled"; 1 -> "Enabled"; else -> "Unknown" }
            row("🧩", "D2 SRP vault read-back", if (status.verified) "Verified" else "Unknown", "Verified", status.verified)
            row("🔒", "Screen lock (D2 SRP)", flag(status.screenLock), "Disabled", status.verified && status.screenLock == 0)
            row("🛠", "Maintenance Mode (D2 SRP)", flag(status.maintenance), "Disabled", status.verified && status.maintenance == 0)
            row("⌨", "Download Mode AT command", flag(status.atCommand), "Enabled", status.verified && status.atCommand == 1)
            row("⬇", "Download Mode eligibility", when (status.downloadAllowed) { true -> "Allowed"; false -> "Blocked"; null -> "Unknown" }, "Allowed", status.verified && status.downloadAllowed == true)
            detail.text = buildString {
                append(status.detail)
                if (status.checkedAt > 0L) append("\nChecked: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(status.checkedAt))}")
            }
        }
        render(app.d2lock.root.DmcRecovery.status(this))
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(body) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val panelWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .82f).toInt(), View.MeasureSpec.AT_MOST))
        dialog.window?.setLayout(panelWidth, scroll.measuredHeight)
        val updates = android.os.Handler(android.os.Looper.getMainLooper())
        var displayed = app.d2lock.root.DmcRecovery.status(this)
        val refresh = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !dialog.isShowing) return
                val current = app.d2lock.root.DmcRecovery.status(this@MainActivity)
                if (current != displayed) { render(current); displayed = current }
                updates.postDelayed(this, 1000L)
            }
        }
        updates.postDelayed(refresh, 1000L)
        dialog.setOnDismissListener { updates.removeCallbacks(refresh) }
        app.d2lock.root.DmcRecovery.refresh(this) { status ->
            if (!isFinishing && !isDestroyed && dialog.isShowing) { render(status); displayed = status }
        }
    }

    private fun runRootAction(action: () -> Pair<Boolean, String>) {
        Thread {
            val result = action()
            runOnUiThread { if (!isDestroyed && !isFinishing) Toast.makeText(this, result.second, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gestureBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            gestureBackCallback = null
        }
        readinessDialog?.dismiss(); readinessDialog=null
        appBiometricSession.cancel()
        appBiometricCancel?.cancel()
        appBiometricCancel = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (authorized) {
            settingsScrollY = findViewById<ViewGroup>(android.R.id.content).findViewWithTag<ScrollView>("settings_scroll")?.scrollY ?: settingsScrollY
            pageScrollPositions[dockDestination]=settingsScrollY
        }
        appStopped = true
        if (authorized) appFingerprintAttempted = false
        authorized = false
        readinessDialog?.dismiss(); readinessDialog=null
        appBiometricSession.cancel()
        appBiometricCancel?.cancel()
        appBiometricCancel = null
        pinDialog?.dismiss()
        pinDialog = null
        setContentView(buildLockedBackdrop())
    }

    private fun runGuardianBiometricTest() {
        val manager = getSystemService(BiometricManager::class.java)
        val status = manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_CAN_AUTH status=$status")
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            GuardianDialogs.showInfo(this, "Fingerprint test unavailable",
                "Android reports that strong biometrics are unavailable (status $status).\n\nCheck your enrolled fingerprint and complete any required Samsung PIN unlock, then try again.",
                icon = R.drawable.ic_guardian_fingerprint)

            return
        }

        val cancel = CancellationSignal()
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("D2 fingerprint test")
            .setSubtitle("Sensor test only")
            .setDescription("Confirm an enrolled strong biometric works. To unlock D2, use Login with fingerprint on the D2 lock screen.")
            .setNegativeButton("Cancel", mainExecutor) { _, _ ->
                Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_CANCELLED")
            }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_REQUESTED")
        prompt.authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_SUCCESS type=${result.authenticationType}")
                recordGuidedResult("sensor", "Android-verified sensor test")
                if (!isFinishing && !isDestroyed) GuardianDialogs.showInfo(this@MainActivity,
                    "Fingerprint test passed",
                    "✓ Android accepted your enrolled strong biometric.\n\nThis sensor test does not change app authorization or dismiss the D2 lock screen. Use Login with fingerprint when opening the D2 app or unlocking its lock screen.",
                    icon = R.drawable.ic_guardian_fingerprint)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_FAILED")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.i("D2FingerprintLab", "GUARDIAN_BIOMETRIC_TEST_ERROR code=$errorCode message=$errString")
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                if (!cancelled && !isFinishing && !isDestroyed) GuardianDialogs.showInfo(this@MainActivity,
                    "Fingerprint test unavailable", "${errString}\n\nError $errorCode. Your D2 unlock method has not changed.",
                    icon = R.drawable.ic_guardian_fingerprint)
            }
        })
    }

    private fun runGuardianEnrollmentComponentTest() {
        val component = ComponentName(
            "com.samsung.android.biometrics.app.setting",
            "com.samsung.android.biometrics.app.setting.fingerprint.enroll.FingerprintEnrollActivity"
        )
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setComponent(component)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        Log.i("D2FingerprintLab", "GUARDIAN_ENROLL_COMPONENT_REQUESTED component=$component")
        try {
            startActivity(intent)
            Log.i("D2FingerprintLab", "GUARDIAN_ENROLL_COMPONENT_LAUNCHED")
            Toast.makeText(
                this,
                "Enrollment component launched. Diagnostic only — do not enroll another fingerprint.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: SecurityException) {
            Log.e("D2FingerprintLab", "GUARDIAN_ENROLL_COMPONENT_BLOCKED_SECURITY", e)
            Toast.makeText(this, "Enrollment component blocked by Android security", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("D2FingerprintLab", "GUARDIAN_ENROLL_COMPONENT_FAILED", e)
            Toast.makeText(this, "Enrollment component launch failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildLockedBackdrop(): ViewGroup {
        // Optional user-selected D2 app wallpaper behind the protected PIN sheet.
        // With no wallpaper selected this remains a neutral AMOLED-style background.
        val host = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Appearance.background(this@MainActivity))
        }
        Prefs.appWallpaper(this)?.let { saved ->
            host.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                runCatching { setImageURI(Uri.parse(saved)) }
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            val dim = Prefs.appWallpaperDim(this).coerceIn(0, 100)
            if (dim > 0) host.addView(View(this).apply {
                setBackgroundColor(Color.argb((255f * dim / 100f).toInt(), 0, 0, 0))
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        // A subtle neutral scrim keeps the glass PIN sheet readable on bright custom images.
        host.addView(View(this).apply {
            setBackgroundColor(Color.argb(34, 0, 0, 0))
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        return host
    }

    private fun buildSettings(): ViewGroup {
        sectionTitles.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(210))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(TextView(this).apply {
            text = "Kiosk D2 Boot Guardian"
            textSize = 32f
            setTextColor(Appearance.text(this@MainActivity))
        })
        root.addView(TextView(this).apply {
            text = "Kiosk D2 Boot Guardian Security Session · Independent app authentication"
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

        section(root, "DEVICE & OVERVIEW")
        root.getChildAt(root.childCount - 1).tag = "settings"
        val categories = if (configured) listOf(
            Triple("Lock Screen", "Unlock, wake behavior, haptics, clock & weather", "lock_screen_settings"),
            Triple("Appearance", "Wallpaper, glass, scale, themes & colors", "app_theme"),
            Triple("Notifications & Media", "Privacy, notification cards & media controls", "notifications"),
            Triple("Shortcuts & Bar", "Floating bar, camera, flashlight & app shortcuts", "floating_bar"),
            Triple("Guardian & Kiosk", "Kiosk authentication, quick-settings guard & call behavior", "guardian_kiosk"),
            Triple("Root & Shizuku", "KernelSU/root mode and Shizuku integration", "root_shizuku"),
            Triple("Recovery & Safety", "System health, ADB/USB recovery & recovery checks", "recovery_safety"),
            Triple("Advanced / Experimental", "Optional and experimental Guardian features", "advanced_settings")
        ) else emptyList()
        settingsSearch.isEnabled = configured
        settingsSearch.alpha = if (configured) 1f else 0.55f
        // Category navigation now lives in the fixed All dock dialog. Keep the
        // actual tagged sections in the scroll view so Search and jump targets work.

        if (configured) {
            val fingerprintIdentity=RootManager.fingerprintHardwareInfo()
            val rootReady=RootManager.isAvailable()
            val shizukuReady=Prefs.shizukuEnabled(this) && runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
            val xposedReady=Prefs.xposedMaster(this)
            val systemUiHealth=KeyguardSignalReceiver.bridgeHealth(this)
            val systemHealth=KeyguardSignalReceiver.systemHealth(this)
            val fingerprintHealth=KeyguardSignalReceiver.fingerprintHealth(this)
            val kioskReady=Prefs.kiosk(this)
            val statusCard = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(18),dp(14),dp(18),dp(14))
                background=Appearance.glass(this@MainActivity,30f,58,true)
                isClickable=true
                isFocusable=true
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_guardian_device)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(20),dp(20)).apply { rightMargin=dp(8) })
                    addView(TextView(this@MainActivity).apply {
                        text="DEVICE IDENTITY"
                        textSize=12f
                        letterSpacing=.10f
                        setTextColor(Appearance.secondary(this@MainActivity))
                    })
                })
                addView(TextView(this@MainActivity).apply {
                    text="${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
                    textSize=20f
                    setTextColor(Appearance.text(this@MainActivity))
                    setPadding(0,dp(4),0,dp(2))
                })
                addView(TextView(this@MainActivity).apply {
                    val fp=fingerprintIdentity?.productId?.takeUnless { it=="Unknown" } ?: "Fingerprint available"
                    text="Android ${android.os.Build.VERSION.RELEASE}  •  API ${android.os.Build.VERSION.SDK_INT}  •  $fp"
                    textSize=12f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(0,0,0,dp(10))
                })
                addView(TextView(this@MainActivity).apply {
                    text=(if (Prefs.enabled(this@MainActivity)) "Guardian enabled" else "Guardian disabled")+"  •  "+Prefs.unlockMethod(this@MainActivity).replaceFirstChar { it.uppercase() }
                    textSize=17f
                    setTextColor(Appearance.text(this@MainActivity))
                    setPadding(0,0,0,dp(8))
                })
                fun statusChip(label:String, ready:Boolean, icon:Int)=LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,18f,if(ready) 44 else 24,ready)
                    setPadding(dp(7),dp(6),dp(7),dp(6))
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(16),dp(16)).apply { rightMargin=dp(5) })
                    addView(TextView(this@MainActivity).apply {
                        text=label+"  "+if(ready) "✓" else "○"; textSize=10.5f
                        setTextColor(if(ready) Appearance.text(this@MainActivity) else Appearance.secondary(this@MainActivity))
                    })
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    addView(statusChip("Kiosk",kioskReady,R.drawable.ic_guardian_shield),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(3) })
                    addView(statusChip("Root",rootReady,R.drawable.ic_guardian_root),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3); rightMargin=dp(3) })
                    addView(statusChip("Shizuku",shizukuReady,R.drawable.ic_guardian_shizuku),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3) })
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    addView(statusChip("Signals enabled",xposedReady,R.drawable.ic_guardian_xposed),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(3) })
                    addView(statusChip("Framework event",systemHealth.status=="CONNECTED",R.drawable.ic_system_health),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3) })
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(12),dp(10),dp(12),dp(10))
                    background=Appearance.glass(this@MainActivity,22f,28,true)
                    addView(TextView(this@MainActivity).apply {
                        text="GUARDIAN SYSTEM BRIDGE"; textSize=11f; letterSpacing=.08f
                        setTextColor(Appearance.secondary(this@MainActivity))
                    })
                    fun bridgeRow(icon:Int,label:String,status:String,active:Boolean,problem:Boolean=false)=LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(5),0,dp(2))
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity)); contentDescription=null
                        },LinearLayout.LayoutParams(dp(18),dp(18)).apply { rightMargin=dp(8) })
                        addView(TextView(this@MainActivity).apply {
                            val stateIcon=if(active) "✓" else if(problem) "⚠" else "○"
                            text="$label  $stateIcon  $status"; textSize=13f
                            setTextColor(when { active -> Color.rgb(102,220,132); problem -> Color.rgb(245,184,72); else -> Appearance.secondary(this@MainActivity) })
                        })
                    }
                    val serverOk=systemHealth.status=="CONNECTED"
                    val uiOk=systemUiHealth.status=="CONNECTED"
                    addView(bridgeRow(R.drawable.ic_system_health,"System Server",if(serverOk) "OBSERVED" else "WAITING",serverOk))
                    addView(bridgeRow(R.drawable.ic_guardian_device,"SystemUI telemetry",if(uiOk) "OBSERVED" else "NO EVENT THIS BOOT",uiOk))
                    addView(bridgeRow(R.drawable.ic_guardian_fingerprint,"Fingerprint telemetry",fingerprintHealth.event ?: "NO EVENT THIS BOOT",fingerprintHealth.event != null))
                    addView(TextView(this@MainActivity).apply {
                        val detail = systemHealth.event?.let { event -> "Last framework event: $event · ${systemHealth.method ?: "unknown"}" }
                            ?: "Waiting for first system_server event after reboot"
                        text=detail; textSize=11f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(5),0,0)
                    })
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_guardian_island)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity)); contentDescription=null
                    },LinearLayout.LayoutParams(dp(20),dp(20)).apply { rightMargin=dp(8) })
                    addView(guardianSwitch().apply {
                        text=if(app.d2lock.bridge.IslandBridge.enabled(this@MainActivity)) "Galaxy Island  •  Enabled" else "Galaxy Island  •  Disabled"
                        textSize=14f; setTextColor(Appearance.text(this@MainActivity))
                        isChecked=app.d2lock.bridge.IslandBridge.enabled(this@MainActivity)
                        setOnCheckedChangeListener { _,value -> app.d2lock.bridge.IslandBridge.setEnabled(this@MainActivity,value) }
                    },LinearLayout.LayoutParams(0,-2,1f))
                })
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    val fp = fingerprintIdentity
                    fun identityRow(label: String, value: String, accent: Boolean = false) = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                        addView(TextView(this@MainActivity).apply {
                            text = label
                            textSize = 12f
                            setTextColor(Appearance.secondary(this@MainActivity))
                        }, LinearLayout.LayoutParams(0, -2, .43f))
                        addView(TextView(this@MainActivity).apply {
                            text = value
                            textSize = 14f
                            gravity = Gravity.END
                            setTextColor(if (accent) Appearance.accent(this@MainActivity) else Appearance.text(this@MainActivity))
                            setTextIsSelectable(true)
                        }, LinearLayout.LayoutParams(0, -2, .57f))
                    }
                    fun identitySection(title: String) = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = Appearance.glass(this@MainActivity, 24f, 30, true)
                        addView(TextView(this@MainActivity).apply {
                            text = title
                            textSize = 11f
                            letterSpacing = .10f
                            setTextColor(Appearance.secondary(this@MainActivity))
                            setPadding(dp(12), dp(5), dp(12), dp(5))
                        })
                    }
                    val identityBody = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(14))
                        background = Appearance.glass(this@MainActivity, 30f, 62, true)
                        addView(TextView(this@MainActivity).apply {
                            text = "KIOSK D2 BOOT GUARDIAN"
                            textSize = 11f
                            letterSpacing = .10f
                            setTextColor(Appearance.secondary(this@MainActivity))
                            setPadding(dp(4), 0, dp(4), dp(2))
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "Device Identity"
                            textSize = 23f
                            setTextColor(Appearance.text(this@MainActivity))
                            setPadding(dp(4), 0, dp(4), dp(10))
                        })
                        addView(identitySection("DEVICE").apply {
                            addView(identityRow("Manufacturer", android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }))
                            addView(identityRow("Model", android.os.Build.MODEL, true))
                            addView(identityRow("Product", android.os.Build.PRODUCT))
                            addView(identityRow("Device", android.os.Build.DEVICE))
                            addView(identityRow("Android", "${android.os.Build.VERSION.RELEASE}  ·  API ${android.os.Build.VERSION.SDK_INT}"))
                            addView(identityRow("Build", android.os.Build.DISPLAY))
                        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                        addView(identitySection("FINGERPRINT HARDWARE").apply {
                            if (fp == null) {
                                addView(identityRow("Status", "Root diagnostic unavailable"))
                            } else {
                                fun addIf(label: String, value: String, accent: Boolean = false) {
                                    if (value != "Not reported" && value != "Unknown") addView(identityRow(label, value, accent))
                                }
                                addIf("Product", fp.productId, true)
                                addIf("Hardware sensor", fp.hardwareSensorId)
                                addIf("Framework sensor", fp.frameworkSensorId)
                                addIf("Chip SN", fp.chipSn)
                                addIf("Firmware", fp.firmwareVersion)
                                addIf("Templates", fp.maxTemplates)
                                addIf("Provider", fp.provider)
                                addIf("HAL deaths", fp.halDeaths)
                            }
                        })
                    }
                    val identityDialog = AlertDialog.Builder(this@MainActivity)
                        .setView(identityBody)
                        .setPositiveButton("Close", null)
                        .create()
                    identityDialog.setOnShowListener {
                        identityDialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 34f, 82, true))
                        identityDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                            setTextColor(Appearance.accent(this@MainActivity))
                            textSize = 15f
                        }
                    }
                    identityDialog.show()
                }
            }
            root.addView(statusCard,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }

        if (!configured) addButton(root, "Create D2 PIN") {
            pinDialog = PinUi.show(this, setup = true, change = false, success = {
                authorized = true
                setContentView(buildSettings())
            })
        }
        if (configured) {
            section(root, "SECURITY CENTER")
            root.getChildAt(root.childCount - 1).tag = "lock_screen_settings"
            val passkeys = runCatching { D2PasskeyStore(this).list() }.getOrDefault(emptyList())
            root.addView(LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(18),dp(14),dp(18),dp(14))
                background=Appearance.glass(this@MainActivity,30f,42,true)

                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_lock_security)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                    addView(TextView(this@MainActivity).apply {
                        text="Security Center"
                        textSize=18f
                        setTextColor(Appearance.text(this@MainActivity))
                    })
                })
                addView(TextView(this@MainActivity).apply {
                    val method=when(Prefs.unlockMethod(this@MainActivity)) { "pattern" -> "Pattern"; "fingerprint" -> "Fingerprint"; else -> "PIN" }
                    text="$method unlock  ·  "+if(passkeys.isEmpty()) "No passkeys" else "${passkeys.size} passkey${if(passkeys.size==1) "" else "s"}"
                    textSize=12f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(0,dp(5),0,dp(10))
                })

                addView(TextView(this@MainActivity).apply {
                    text="Unlock method"
                    textSize=15f
                    setTextColor(Appearance.text(this@MainActivity))
                    gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,22f,26,true)
                    setPadding(dp(11),dp(7),dp(11),dp(7))
                    setOnClickListener {
                        val methods=arrayOf("PIN","Pattern","Login with fingerprint")
                        val current=when(Prefs.unlockMethod(this@MainActivity)) { "pattern" -> 1; "fingerprint" -> 2; else -> 0 }
                        GuardianDialogs.showChoices(this@MainActivity, "D2 unlock method", methods, current) { d,which ->
                                if(which==2) {
                                    d.dismiss()
                                    val biometricStatus=getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                    if(biometricStatus==BiometricManager.BIOMETRIC_SUCCESS) {
                                        Prefs.setUnlockMethod(this@MainActivity,"fingerprint")
                                        setContentView(buildSettings())
                                        GuardianDialogs.showInfo(this@MainActivity,
                                            title = "Login with fingerprint",
                                            message = "✓ Keep your fingerprint enrolled.\n\n1. Open Samsung Settings → Fingerprints.\n2. Turn Fingerprint unlock OFF.\n3. Use your enrolled fingerprint to unlock D2.\n\nIf Samsung asks for its PIN first, complete that screen before D2 fingerprint login.\n\nFingerprint login also opens the D2 app and protected settings. Your D2 PIN remains the fallback if biometrics are unavailable or you cancel.",
                                            icon = R.drawable.ic_guardian_fingerprint,
                                            secondary = "Open settings",
                                            onSecondary = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
                                    } else {
                                        GuardianDialogs.showInfo(this@MainActivity,
                                            title = "Fingerprint unavailable",
                                            message = "Enroll a fingerprint in Samsung Settings and complete any required Samsung PIN unlock, then try again.\n\nYour existing D2 unlock method has been kept.",
                                            icon = R.drawable.ic_guardian_fingerprint,
                                            primary = "Open settings",
                                            onPrimary = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                                            secondary = "Cancel")
                                    }
                                } else if(which==0) {
                                    Prefs.setUnlockMethod(this@MainActivity,"pin")
                                    d.dismiss()
                                    setContentView(buildSettings())
                                } else if(PatternStore(this@MainActivity).configured()) {
                                    Prefs.setUnlockMethod(this@MainActivity,"pattern")
                                    d.dismiss()
                                    setContentView(buildSettings())
                                } else {
                                    d.dismiss()
                                    PatternUi.show(this@MainActivity,setup=true,success={
                                        Prefs.setUnlockMethod(this@MainActivity,"pattern")
                                        setContentView(buildSettings())
                                    })
                                }
                            }
                    }
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })

                addView(TextView(this@MainActivity).apply {
                    text="Change PIN"
                    textSize=15f
                    setTextColor(Appearance.text(this@MainActivity))
                    gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,22f,26,true)
                    setPadding(dp(14),dp(10),dp(14),dp(10))
                    setOnClickListener {
                        pinDialog = PinUi.show(this@MainActivity, setup=false, change=true, success={
                            authorized=true
                            setContentView(buildSettings())
                        })
                    }
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })

                addView(TextView(this@MainActivity).apply {
                    text="Saved passkeys (provider unavailable)"
                    textSize=15f
                    setTextColor(Appearance.text(this@MainActivity))
                    gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,22f,26,true)
                    setPadding(dp(14),dp(10),dp(14),dp(10))
                    setOnClickListener {
                        val content=LinearLayout(this@MainActivity).apply {
                            orientation=LinearLayout.VERTICAL
                            setPadding(dp(18),dp(10),dp(18),dp(24))
                        }
                        content.addView(TextView(this@MainActivity).apply {
                            text=if(passkeys.isEmpty()) "No D2 passkeys saved yet." else "${passkeys.size} saved D2 passkey${if(passkeys.size==1) "" else "s"}"
                            textSize=14f
                            setTextColor(Appearance.secondary(this@MainActivity))
                            setPadding(0,0,0,dp(8))
                        })
                        passkeys.forEach { record ->
                            content.addView(TextView(this@MainActivity).apply {
                                text="${record.userName.ifBlank { record.displayName.ifBlank { "Passkey" } }}  ·  ${record.rpId}"
                                textSize=14f
                                setTextColor(Appearance.text(this@MainActivity))
                                background=Appearance.glass(this@MainActivity,20f,24,true)
                                setPadding(dp(14),dp(11),dp(14),dp(11))
                                setOnClickListener {
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle(record.userName.ifBlank { "D2 passkey" })
                                        .setMessage("Site / app: ${record.rpId}\nDisplay name: ${record.displayName.ifBlank { "Not provided" }}")
                                        .setNegativeButton("Close",null)
                                        .setPositiveButton("Delete") { _,_ ->
                                            runCatching { D2PasskeyStore(this@MainActivity).delete(record.id) }
                                            setContentView(buildSettings())
                                        }.show()
                                }
                            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
                        }
                        val dialog=AlertDialog.Builder(this@MainActivity)
                            .setTitle("Passkey Manager")
                            .setView(content)
                            .setNegativeButton("Close",null)
                            .setPositiveButton("Credential settings") { _,_ ->
                                val intents=listOf(Intent("android.settings.CREDENTIAL_PROVIDER"),Intent(Settings.ACTION_SECURITY_SETTINGS))
                                val target=intents.firstOrNull { it.resolveActivity(packageManager)!=null }
                                if(target!=null) startActivity(target)
                                else Toast.makeText(this@MainActivity,"Credential provider settings are unavailable on this build.",Toast.LENGTH_LONG).show()
                            }.create()
                        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,30f,76,true)) }
                        dialog.show()
                    }
                })
                addView(TextView(this@MainActivity).apply {
                    text="Your 6-digit D2 PIN remains the recovery unlock method. D2 and Samsung use separate credentials. Samsung may still require its PIN after reboot or when strong authentication is required."
                    textSize=12f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(dp(4),dp(10),dp(4),0)
                })
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
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
                    query.contains("root") || query.contains("kernelsu") || query.contains("shizuku") -> "root_shizuku"
                    query.contains("recovery") || query.contains("adb") || query.contains("usb") || query.contains("health") -> "recovery_safety"
                    query.contains("kiosk") || query.contains("guard") || query.contains("call") -> "guardian_kiosk"
                    query.contains("notification") || query.contains("privacy") || query.contains("media") -> "notifications"
                    query.contains("floating") || query.contains("slide") || query.contains("shortcut") || query.contains("camera") || query.contains("flashlight") -> "floating_bar"
                    query.contains("theme") || query.contains("glass") || query.contains("wallpaper") || query.contains("color") || query.contains("scale") -> "app_theme"
                    query.contains("profile") || query.contains("experimental") || query.contains("advanced") -> "advanced_settings"
                    else -> "lock_screen_settings"
                }
                revealCompactDetails(root)
                    root.findViewWithTag<android.view.View>(target)?.let { view ->
                    root.post {
                        val rect = android.graphics.Rect()
                        view.getDrawingRect(rect)
                        root.offsetDescendantRectToMyCoords(view, rect)
                        (root.parent as? ScrollView)?.smoothScrollTo(0, (rect.top - dp(18)).coerceAtLeast(0))
                        view.postDelayed({ flashSettingsTarget(view) }, 280)
                    }
                }
            }
            true
        }
        val versionInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        root.addView(TextView(this).apply {
            val versionName = versionInfo?.versionName ?: "current"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) versionInfo?.longVersionCode ?: 0L else @Suppress("DEPRECATION") (versionInfo?.versionCode?.toLong() ?: 0L)
            text = "Kiosk D2 Boot Guardian  •  $versionName  ($versionCode)"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(8), dp(2), dp(8), dp(10))
        }, LinearLayout.LayoutParams(-1, -2))

        return settingsHost(root)
        }
        addButton(root, "What's New & Next") {
            val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            showWhatsNewNext(info?.versionName ?: "current", null)
        }
        val taps = DoubleTap()
        section(root, "LOCK & WAKE")
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Appearance.glass(this@MainActivity, 30f, 38, true)
            addView(TextView(this@MainActivity).apply {
                text = "Double-tap to lock D2"; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity))
                background = Appearance.glass(this@MainActivity, 22f, 28, true)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                setOnClickListener { if (taps.tap(SystemClock.elapsedRealtime())) startActivity(Intent(this@MainActivity, LockScreenActivity::class.java)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(guardianSwitch().apply {
                text = "Show D2 when the screen wakes"; textSize = 15f
                setTextColor(Appearance.text(this@MainActivity)); isChecked = Prefs.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setEnabled(this@MainActivity, checked)
                    if (checked) LockScreenService.start(this@MainActivity) else LockScreenService.stop(this@MainActivity)
                }
            }, rowParams())
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        section(root, "HOME SCREEN WIDGET")
        root.addView(TextView(this).apply {
            text = "Widget Manager"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Appearance.text(this@MainActivity)); background = Appearance.glass(this@MainActivity, 24f, 34, true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                val info = TextView(this@MainActivity).apply {
                    text = "Add the customizable Kiosk D2 Boot Guardian widget. Each placed widget can have its own style, tap action and label. Long-press a widget to open launcher reconfiguration when supported."
                    textSize = 14f; setTextColor(Appearance.text(this@MainActivity)); setPadding(dp(18), dp(10), dp(18), dp(18))
                }
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Widget Manager").setView(info)
                    .setNegativeButton("Close", null).setPositiveButton("Add widget") { _, _ ->
                        val manager = getSystemService(AppWidgetManager::class.java)
                        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this@MainActivity, D2Widget::class.java), null, null)
                        else Toast.makeText(this@MainActivity, "Open your launcher's Widgets menu and add Kiosk D2 Boot Guardian.", Toast.LENGTH_LONG).show()
                    }.create()
                dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true)) }
                dialog.show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        section(root, "D2 SRP")
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Appearance.glass(this@MainActivity, 28f, 36, true)
            addView(TextView(this@MainActivity).apply {
                text = "D2 SRP  •  Samsung Recovery Protection"
                textSize = 18f
                setTextColor(Appearance.text(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Automatic after boot via D2’s Xposed System Server hook. Enable D2 for System Framework in LSPosed.\nTargets: Screen lock & Maintenance Mode disabled\nDownload Mode AT Command enabled • Download Mode allowed"
                textSize = 13f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(TextView(this@MainActivity).apply {
                val current = app.d2lock.root.DmcRecovery.status(this@MainActivity)
                text = "System Server result: check recovery log\nRoot fallback: ${current.detail}"
                textSize = 12f
                setTextColor(Appearance.secondary(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text = "✓  View feature status"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = Appearance.glass(this@MainActivity, 22f, 30, true)
                isClickable = true
                isFocusable = true
                contentDescription = "View D2 SRP feature status"
                setOnClickListener { showDmcStatus() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        section(root, "SYSTEM INTEGRATION")
        root.getChildAt(root.childCount - 1).tag = "root_shizuku"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,28f,36,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_integrations)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text="System Integration Center"; textSize=18f; setTextColor(Appearance.text(this@MainActivity))
                })
            })
            addView(TextView(this@MainActivity).apply {
                val shizuku=if(Prefs.shizukuEnabled(this@MainActivity)) "Shizuku ON" else "Shizuku OFF"
                val rootState=if(Prefs.rootMode(this@MainActivity) && RootManager.isAvailable()) "Root ON" else "Root OFF"
                val xposed=if(Prefs.xposedMaster(this@MainActivity)) "Xposed ON" else "Xposed OFF"
                text="$shizuku  •  $rootState  •  $xposed"
                textSize=12f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(5),0,dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                val rootOk = !Prefs.rootMode(this@MainActivity) || RootManager.isAvailable()
                val shizukuOk = !Prefs.shizukuEnabled(this@MainActivity) || runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                val islandOk = !app.d2lock.bridge.IslandBridge.enabled(this@MainActivity) || Prefs.xposedGalaxyIsland(this@MainActivity)
                val attention = buildList {
                    if (!rootOk) add("Root unavailable")
                    if (!shizukuOk) add("Shizuku unavailable")
                    if (!islandOk) add("Galaxy Island bridge needs attention")
                }
                text = if (attention.isEmpty()) "Guardian Health  •  integrations ready" else "Guardian Health  •  " + attention.joinToString("  •  ")
                textSize=12f
                setTextColor(if (attention.isEmpty()) Appearance.secondary(this@MainActivity) else Appearance.accent(this@MainActivity))
                setPadding(0,0,0,dp(10))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                fun healthBubble(label:String, action:()->Unit)=TextView(this@MainActivity).apply {
                    text=label; textSize=13f; gravity=Gravity.CENTER
                    setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,28,true)
                    setPadding(dp(10),dp(10),dp(10),dp(10))
                    setOnClickListener { action() }
                }
                addView(healthBubble("Test setup") {
                    val checks = listOf(
                        "D2 authentication" to PinStore(this@MainActivity).configured(),
                        "Lock on wake" to Prefs.enabled(this@MainActivity),
                        "Kiosk protection" to Prefs.kiosk(this@MainActivity),
                        "Root integration" to (!Prefs.rootMode(this@MainActivity) || RootManager.isAvailable()),
                        "Shizuku" to (!Prefs.shizukuEnabled(this@MainActivity) || runCatching { Shizuku.pingBinder() }.getOrDefault(false)),
                        "Xposed configuration" to Prefs.xposedMaster(this@MainActivity),
                        "Automatic fallback" to Prefs.xposedAutomaticFallback(this@MainActivity),
                        "Galaxy Island sync" to (!app.d2lock.bridge.IslandBridge.enabled(this@MainActivity) || Prefs.xposedIslandGuardianSync(this@MainActivity))
                    )
                    val report = checks.joinToString("\n") { (name, ok) -> (if(ok) "✓ " else "○ ") + name }
                    AlertDialog.Builder(this@MainActivity).setTitle("Guardian setup test")
                        .setMessage(report + "\n\nFingerprint unlock remains experimental until the authenticated SystemUI callback is verified.")
                        .setPositiveButton("Done",null).create().also {
                            it.setOnShowListener { _ -> it.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,30f,76,true)) }
                            it.show()
                        }
                },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(5) })
                addView(healthBubble("Copy diagnostics") {
                    val info=runCatching { packageManager.getPackageInfo(packageName,0) }.getOrNull()
                    val report=buildString {
                        appendLine("Kiosk D2 Boot Guardian diagnostics")
                        appendLine("Version: ${info?.versionName ?: "unknown"}")
                        appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                        appendLine("Device: ${android.os.Build.MODEL}")
                        appendLine("D2 enabled: ${Prefs.enabled(this@MainActivity)}")
                        appendLine("Kiosk: ${Prefs.kiosk(this@MainActivity)}")
                        appendLine("Root configured/available: ${Prefs.rootMode(this@MainActivity)}/${RootManager.isAvailable()}")
                        appendLine("Shizuku configured/available: ${Prefs.shizukuEnabled(this@MainActivity)}/${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}")
                        appendLine("Xposed configured: ${Prefs.xposedMaster(this@MainActivity)}")
                        appendLine("Automatic fallback: ${Prefs.xposedAutomaticFallback(this@MainActivity)}")
                        appendLine("Galaxy Island paired: ${app.d2lock.bridge.IslandBridge.enabled(this@MainActivity)}")
                        appendLine("Galaxy Island Guardian sync: ${Prefs.xposedIslandGuardianSync(this@MainActivity)}")
                        appendLine("Fingerprint unlock: experimental/not enabled")
                    }
                    val clipboard=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Kiosk D2 Boot Guardian diagnostics",report))
                    showD2Message("Sanitized Guardian diagnostics copied")
                },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(5) })
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="Open System Server Command Center"; textSize=15f; gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,34,true)
                setPadding(dp(14),dp(11),dp(14),dp(11))
                setOnClickListener { showSystemServerDashboard() }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            addView(TextView(this@MainActivity).apply {
                text="Open Guardian Log Center"; textSize=15f; gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,34,true)
                setPadding(dp(14),dp(11),dp(14),dp(11)); setOnClickListener { showGuardianLogCenter() }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })

            addView(TextView(this@MainActivity).apply {
                text="Open integrations"; textSize=15f; gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,28,true)
                setPadding(dp(14),dp(10),dp(14),dp(10))
                setOnClickListener {
                    val panel=LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.VERTICAL
                        setPadding(dp(18),dp(8),dp(18),dp(20))
                        fun integrationRow(label:String, checked:Boolean, onChange:(Boolean)->Unit)=LinearLayout(this@MainActivity).apply {
                            orientation=LinearLayout.VERTICAL
                            addView(guardianSwitch().apply {
                                text=label; isChecked=checked
                                if (label == "Automatic fallback") {
                                    text = "Automatic fallback (not implemented)"; isEnabled = false; isChecked = false
                                }
                                setTextColor(Appearance.text(this@MainActivity))
                                background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                                setPadding(dp(2),dp(10),dp(2),dp(10))
                                setOnCheckedChangeListener { _,value -> onChange(value) }
                            },LinearLayout.LayoutParams(-1,-2))
                            addView(View(this@MainActivity).apply {
                                setBackgroundColor(Color.argb(34,255,255,255))
                            },LinearLayout.LayoutParams(-1,dp(1)))
                        }
                        addView(integrationRow("Use Shizuku",Prefs.shizukuEnabled(this@MainActivity)) { checked ->
                            Prefs.setShizukuEnabled(this@MainActivity,checked)
                        })
                        addView(integrationRow("KernelSU Root Mode (verified path)",Prefs.rootMode(this@MainActivity)) { checked ->
                            if(checked && !RootManager.isAvailable()) {
                                Toast.makeText(this@MainActivity,"Root shell was not detected",Toast.LENGTH_LONG).show()
                            } else Prefs.setRootMode(this@MainActivity,checked)
                        })
                        addView(integrationRow("LSPosed integration (minimal systemReady)",Prefs.xposedMaster(this@MainActivity)) { checked ->
                            Prefs.setXposedMaster(this@MainActivity,checked)
                        })
                        addView(integrationRow("Galaxy Island • Xposed",Prefs.xposedGalaxyIsland(this@MainActivity)) { checked ->
                            Prefs.setXposedGalaxyIsland(this@MainActivity,checked)
                        })
                        addView(integrationRow("Automatic fallback",Prefs.xposedAutomaticFallback(this@MainActivity)) { checked ->
                            Prefs.setXposedAutomaticFallback(this@MainActivity,checked)
                        })

                    }
                    val dialog=AlertDialog.Builder(this@MainActivity)
                        .setTitle("System integrations")
                        .setView(panel)
                        .setPositiveButton("Done") { _,_ -> setContentView(buildSettings()) }
                        .create()
                    dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,30f,76,true)) }
                    dialog.show()
                }
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "RECOVERY & SAFETY")
        root.getChildAt(root.childCount - 1).tag = "recovery_safety"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,28f,36,true)
            addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_guardian_safety)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                    addView(TextView(this@MainActivity).apply {
                        text="Safety Center"
                        textSize=18f
                        setTextColor(Appearance.text(this@MainActivity))
                    })
                })
            addView(TextView(this@MainActivity).apply {
                text="Run Setup Wizard Again"; textSize=14f; gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,28,true)
                setPadding(dp(12),dp(10),dp(12),dp(10)); setOnClickListener { showSetupWizard() }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                val rootReady=RootManager.isAvailable()
                val adb=Prefs.adbRecovery(this@MainActivity)
                text=listOf(
                    if(rootReady) "Root available" else "Root unavailable",
                    if(adb) "ADB recovery configured" else "ADB recovery off",
                    if(Prefs.kiosk(this@MainActivity)) "Kiosk protection on" else "Kiosk protection off"
                ).joinToString("  ·  ")
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(5),0,dp(12))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                val health=TextView(this@MainActivity).apply {
                    text="  System health"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_system_health,0,0,0)
                    compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    compoundDrawablePadding=dp(6)
                    textSize=14f
                    setTextColor(Appearance.text(this@MainActivity))
                    gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,22f,26,true)
                    setPadding(dp(14),dp(10),dp(14),dp(10))
                    setOnClickListener {
                        val content=LinearLayout(this@MainActivity).apply {
                            orientation=LinearLayout.VERTICAL
                            setPadding(dp(16),dp(8),dp(16),dp(20))
                            addView(systemHealthCard())
                        }
                        val dialog=AlertDialog.Builder(this@MainActivity)
                            .setTitle("System health")
                            .setView(content)
                            .setPositiveButton("Done",null)
                            .create()
                        dialog.setOnShowListener {
                            dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,32f,82,true))
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                        }
                        dialog.show()
                    }
                }
                addView(health,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
                val refresh=TextView(this@MainActivity).apply {
                    text="  Refresh"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh,0,0,0)
                    compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    compoundDrawablePadding=dp(6)
                    textSize=14f
                    setTextColor(Appearance.accent(this@MainActivity))
                    gravity=Gravity.CENTER
                    background=Appearance.glass(this@MainActivity,22f,26,true)
                    setPadding(dp(14),dp(10),dp(14),dp(10))
                    setOnClickListener { refreshShizukuUi() }
                }
                addView(refresh,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "GUARDIAN & KIOSK")
        root.getChildAt(root.childCount - 1).tag = "guardian_kiosk"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_guardian_shield)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                    addView(TextView(this@MainActivity).apply {
                        text="Guardian Protection"
                        textSize=18f
                        setTextColor(Appearance.text(this@MainActivity))
                    })
                })
            addView(TextView(this@MainActivity).apply {
                text=listOf(
                    if(Prefs.quickSettingsGuard(this@MainActivity)) "Quick Settings guarded" else "Quick Settings guard off",
                    if(Prefs.kiosk(this@MainActivity)) "Kiosk authentication on" else "Kiosk authentication off"
                ).joinToString("  ·  ")
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(5),0,dp(10))
            })
            addView(guardianSwitch().apply {
                text="Guard Quick Settings while D2 is locked"
                textSize=16f
                setTextColor(Appearance.text(this@MainActivity))
                isChecked=Prefs.quickSettingsGuard(this@MainActivity)
                setOnCheckedChangeListener { _,checked -> Prefs.setQuickSettingsGuard(this@MainActivity,checked) }
            },rowParams())
            addView(TextView(this@MainActivity).apply {
                text="Keeps the notification shade collapsed while the D2 lock screen has focus. Emergency and recovery controls remain available."
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(4),0,dp(4),dp(10))
            })
            addView(guardianSwitch().apply {
                text="Require D2 authentication to leave"
                textSize=16f
                setTextColor(Appearance.text(this@MainActivity))
                isChecked=Prefs.kiosk(this@MainActivity)
                setOnCheckedChangeListener { _,checked -> Prefs.setKiosk(this@MainActivity,checked) }
            },rowParams())
            addView(TextView(this@MainActivity).apply {
                text="Root kiosk blocks Home and Recents until the selected D2 unlock method is accepted. Phone-call screens remain allowed; reboot and root are recovery bypasses."
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(4),0,dp(4),0)
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "FINGERPRINT CENTER")
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_fingerprint)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    contentDescription=null
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text="Fingerprint Center"
                    textSize=18f
                    setTextColor(Appearance.text(this@MainActivity))
                })
            })
            val fingerprintHardware=RootManager.fingerprintHardwareInfo()
            addView(TextView(this@MainActivity).apply {
                text=if(fingerprintHardware!=null) {
                    "BIOMETRIC_STRONG  •  ${fingerprintHardware.productId}  •  Sensor ${fingerprintHardware.frameworkSensorId}"
                } else "BIOMETRIC_STRONG  •  Diagnostic ready"
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(4),0,dp(10))
            })
            if(fingerprintHardware!=null) addView(TextView(this@MainActivity).apply {
                val id=fingerprintHardware.hardwareSensorId
                val shortId=if(id.length>16) id.take(8)+"…"+id.takeLast(6) else id
                text="Hardware ID  •  $shortId   Firmware  •  ${fingerprintHardware.firmwareVersion}   HAL deaths  •  ${fingerprintHardware.halDeaths}"
                textSize=11f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,0,0,dp(12))
                isClickable=true
                isFocusable=true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Fingerprint Hardware")
                        .setMessage("Product ID: ${fingerprintHardware.productId}\nHardware Sensor ID: ${fingerprintHardware.hardwareSensorId}\nFramework Sensor ID: ${fingerprintHardware.frameworkSensorId}\nChip SN: ${fingerprintHardware.chipSn}\nFirmware: ${fingerprintHardware.firmwareVersion}\nMax templates: ${fingerprintHardware.maxTemplates}\nProvider: ${fingerprintHardware.provider}\nHAL deaths since reboot: ${fingerprintHardware.halDeaths}")
                        .setPositiveButton("Copy hardware ID") { _, _ ->
                            val clipboard=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Fingerprint hardware sensor ID",fingerprintHardware.hardwareSensorId))
                            Toast.makeText(this@MainActivity,"Fingerprint hardware ID copied",Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Close",null)
                        .show()
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                val test=TextView(this@MainActivity).apply {
                    text="Test Fingerprint"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_guardian_fingerprint,0,0,0)
                    compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    compoundDrawablePadding=dp(6)
                    textSize=14f
                    gravity=Gravity.CENTER
                    setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,28,true)
                    setPadding(dp(10),dp(13),dp(10),dp(13))
                    isClickable=true
                    isFocusable=true
                    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); runGuardianBiometricTest() }
                }
                val enroll=TextView(this@MainActivity).apply {
                    text="Enrollment"
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_guardian_enroll,0,0,0)
                    compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    compoundDrawablePadding=dp(6)
                    textSize=14f
                    gravity=Gravity.CENTER
                    setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,28,true)
                    setPadding(dp(10),dp(13),dp(10),dp(13))
                    isClickable=true
                    isFocusable=true
                    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); runGuardianEnrollmentComponentTest() }
                }
                addView(test,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
                addView(enroll,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
            })
            addView(TextView(this@MainActivity).apply {
                text="ⓘ Sensor test only • fingerprint login is used on the D2 lock screen"
                textSize=11f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(10),0,0)
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "RECOVERY CENTER")
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_recovery)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    contentDescription=null
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text="Recovery Center"
                    textSize=18f
                    setTextColor(Appearance.text(this@MainActivity))
                })
            })
            addView(TextView(this@MainActivity).apply {
                text=if(RootManager.isAvailable()) "Root available  •  Recovery ready" else "Root unavailable  •  Recovery actions disabled"
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(4),0,dp(12))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                fun recoveryAction(label:String, action:()->Unit)=TextView(this@MainActivity).apply {
                    text=label
                    textSize=14f
                    gravity=Gravity.CENTER
                    setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,28,true)
                    setPadding(dp(10),dp(13),dp(10),dp(13))
                    isClickable=true
                    isFocusable=true
                    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); action() }
                }
                val systemUi=recoveryAction("Restart SystemUI") {
                    if(!RootManager.isAvailable()) Toast.makeText(this@MainActivity,"KernelSU/root access is required",Toast.LENGTH_LONG).show()
                    else GuardianDialogs.showInfo(this@MainActivity, "Restart SystemUI?",
                        "SystemUI will briefly disappear and reload. D2 will verify Samsung Recovery Protection before continuing. Failed, unknown or timed-out verification cancels this action.",
                        primary = "Check & restart", secondary = "Cancel", onPrimary = {
                            Toast.makeText(this@MainActivity, "D2 SRP: checking before SystemUI restart…", Toast.LENGTH_LONG).show()
                            app.d2lock.root.DmcRecovery.verifyForAction(this@MainActivity) { status ->
                                if (!isFinishing && !isDestroyed && authorized && !appStopped) {
                                    if (status.targetsMet) runRootAction { RootManager.restartSystemUi() }
                                    else GuardianDialogs.showInfo(this@MainActivity, "Restart cancelled", "D2 SRP could not be verified.\n\n${status.detail}\n\nCheck Feature status and retry.")
                                }
                            }
                        })
                }
                val reboot=recoveryAction("Soft restart") {
                    GuardianDialogs.showInfo(this@MainActivity, "Soft restart Android?",
                        "Android’s framework will restart; unsaved work may be lost. System Server performs a fresh D2 SRP check before continuing. Failed, unknown or timed-out verification cancels the restart. Requires D2’s LSPosed System Framework integration.",
                        primary = "Check & restart", secondary = "Cancel", onPrimary = {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                sendBroadcast(Intent("app.d2lock.action.DMC_SOFT_RESTART").setPackage("android"), null,
                                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                                Toast.makeText(this@MainActivity, "Guarded soft restart requested. If System Server is unavailable, no restart will occur.", Toast.LENGTH_LONG).show()
                            } else Toast.makeText(this@MainActivity, "Guarded soft restart requires Android 14 or newer", Toast.LENGTH_LONG).show()
                        })
                }
                addView(systemUi,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
                addView(reboot,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
            })
            addView(TextView(this@MainActivity).apply {
                text="D2 SRP verification • confirmation required • SystemUI restart requires root"
                textSize=11f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(10),0,0)
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "ADB / USB RECOVERY")
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_usb_recovery)
                        imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                        contentDescription=null
                    },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                    addView(TextView(this@MainActivity).apply {
                        text="USB Recovery"
                        textSize=18f
                        setTextColor(Appearance.text(this@MainActivity))
                    })
                })
            addView(TextView(this@MainActivity).apply {
                val state=RootManager.usbAdbState()
                text=when {
                    state==null -> "Root access is required to inspect Samsung USB/ADB state."
                    Prefs.adbRecovery(this@MainActivity) && state.adbEnabled!="1" -> "Recovery configured · USB debugging currently off · "+state.summary
                    else -> state.summary
                }
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(5),0,dp(10))
            })
            addView(guardianSwitch().apply {
                text="ADB / USB recovery mode"
                textSize=16f
                setTextColor(Appearance.text(this@MainActivity))
                isChecked=Prefs.adbRecovery(this@MainActivity)
                var correcting = false
                setOnCheckedChangeListener { _,checked ->
                    if (correcting) return@setOnCheckedChangeListener
                    if(!RootManager.isAvailable()) {
                        correcting = true; isChecked = !checked; correcting = false
                        Toast.makeText(this@MainActivity,"KernelSU/root access is required",Toast.LENGTH_LONG).show()
                        return@setOnCheckedChangeListener
                    }
                    isEnabled = false
                    Thread {
                        val result = if (checked) RootManager.enableUsbAdbRecovery(this@MainActivity) else RootManager.disableUsbAdbRecovery(this@MainActivity)
                        runOnUiThread {
                            if (isDestroyed || isFinishing) return@runOnUiThread
                            // Remove the listener before correcting a failed operation.
                            if (result.first) Prefs.setAdbRecovery(this@MainActivity, checked)
                            else { correcting = true; isChecked = !checked; correcting = false }
                            isEnabled = true
                            Toast.makeText(this@MainActivity, result.second, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            },rowParams())
            addView(TextView(this@MainActivity).apply {
                text="Root-only recovery option. D2 requests block_usb_lock=0 and the ADB USB function. Samsung firmware can still override USB policy, and computers still require ADB authorization."
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(4),0,dp(4),0)
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })

        section(root, "THEME & COLORS")
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),dp(5),dp(8),dp(5))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            isClickable=true
            isFocusable=true
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text="Appearance & Theme"
                    textSize=16f
                    setTextColor(Appearance.text(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text="Theme, accent, glass, colors and floating bar"
                    textSize=11f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(0,dp(3),0,0)
                })
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(TextView(this@MainActivity).apply {
                text="Customize  ›"
                textSize=14f
                setTextColor(Appearance.accent(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,26,true)
                setPadding(dp(14),dp(10),dp(14),dp(10))
            })
            setOnClickListener {
                val content=LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.VERTICAL
                    // Guardian dialog standard: comfortable side gutters and
                    // extra bottom clearance above the fixed navigation surface.
                    setPadding(dp(18),dp(8),dp(18),dp(72))
                }
                ThemeOptions.add(this@MainActivity,content,::refreshAppearance)
                val scroll=ScrollView(this@MainActivity).apply {
                    isFillViewport=true
                    addView(content)
                }
                val dialog=AlertDialog.Builder(this@MainActivity)
                    .setTitle("Appearance & Theme")
                    .setView(scroll)
                    .setPositiveButton("Done",null)
                    .create()
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,32f,96,true))
                    dialog.window?.setDimAmount(0.72f)
                    dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    dialog.window?.setLayout((resources.displayMetrics.widthPixels*0.94f).toInt(),(resources.displayMetrics.heightPixels*0.86f).toInt())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                }
                dialog.show()
            }
        },LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(6) })
        section(root, "ICON CENTER")
        val iconOptions = IconManager.options
        val currentIcon = IconManager.selected(this)
        val iconPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = Appearance.glass(this@MainActivity, 24f, 28, true)
        }
        val iconPreviewImage = ImageView(this).apply {
            setImageResource(IconManager.iconResource(currentIcon))
            contentDescription = "Selected D2 launcher icon"
        }
        val iconPreviewText = TextView(this).apply {
            text = (iconOptions.firstOrNull { it.key == currentIcon }?.label ?: "Titanium Graphite") + "  •  Active"
            textSize = 14f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(14), 0, 0, 0)
        }
        iconPreview.addView(iconPreviewImage, LinearLayout.LayoutParams(dp(54), dp(54)))
        iconPreview.addView(iconPreviewText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(iconPreview, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(4) })
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
                iconPreviewText.text = option.label + "  •  Active"
                refreshIconPicker(option.key)
                showD2Message(option.label + " applied")
            }
            iconCells[option.key] = cell
            iconPicker.addView(cell, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        refreshIconPicker(currentIcon)
        root.addView(iconPicker, LinearLayout.LayoutParams(-1, -2))

        val iconActions = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val resetIconAction = TextView(this).apply { text="Reset"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,28,true); setPadding(dp(10),dp(12),dp(10),dp(12)); isClickable=true; isFocusable=true }
        resetIconAction.setOnClickListener {
            IconManager.reset(this)
            showD2Message("Titanium Graphite restored")
        }
        val diagnosticsAction = TextView(this).apply { text="Diagnostics"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,22f,28,true); setPadding(dp(10),dp(12),dp(10),dp(12)); isClickable=true; isFocusable=true }
        diagnosticsAction.setOnClickListener {
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
        iconActions.addView(resetIconAction,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
        iconActions.addView(diagnosticsAction,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
        root.addView(iconActions,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        root.addView(TextView(this).apply {
            text = "ⓘ Theme Park or another icon pack can override the selected D2 launcher icon."
            textSize = 11f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), 0, dp(4), dp(10))
            setOnClickListener { GuardianDialogs.showInfo(this@MainActivity, "Launcher icon help", "If the preview changes but the Home screen icon does not, temporarily apply Samsung’s default icons and test again.") }
        })

        section(root, "APP THEME")
        root.getChildAt(root.childCount - 1).tag = "app_theme"
        val appearanceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2)); background = Appearance.glass(this@MainActivity, 22f, 26, true) }
        root.addView(appearanceCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        section(appearanceCard, "LOCK SCREEN EXTRAS")
        appearanceCard.addView(guardianSwitch().apply {
            text = "Wallpaper parallax"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.wallpaperParallax(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setWallpaperParallax(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(40)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Glass shimmer"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.glassShimmer(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setGlassShimmer(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(40)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Double-tap empty lock screen to sleep"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.doubleTapSleep(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setDoubleTapSleep(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(40)))
        addChoice(appearanceCard, "Turn off screen when lock is idle", listOf("Off", "15 seconds", "30 seconds", "1 minute", "2 minutes"),
            listOf(0, 15, 30, 60, 120).indexOf(Prefs.lockIdleSleepSeconds(this)).coerceAtLeast(0)) {
            Prefs.setLockIdleSleepSeconds(this, listOf(0, 15, 30, 60, 120)[it])
        }
        addChoice(appearanceCard, "Unlock haptics", listOf("Off", "Soft", "Medium", "Strong"),
            listOf("off", "soft", "medium", "strong").indexOf(Prefs.unlockHaptics(this)).coerceAtLeast(0)) {
            Prefs.setUnlockHaptics(this, listOf("off", "soft", "medium", "strong")[it])
        }
        appearanceCard.addView(TextView(this).apply {
            text = "Optional effects are off by default and work with any wallpaper."
            textSize = 12f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(3))
        })

        appearanceCard.addView(TextView(this).apply {
            text = "Dim wallpaper: ${Prefs.wallpaperDim(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), 0, 0, 0)
            tag = "wallpaper_dim_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 80
            progress = Prefs.wallpaperDim(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setWallpaperDim(this@MainActivity, value)
                    (root.findViewWithTag<TextView>("wallpaper_dim_label"))?.text = "Dim wallpaper: $value%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(20)).apply { bottomMargin = dp(4) })
        appearanceCard.addView(TextView(this).apply {
            text = "Lock-screen glass: ${Prefs.lockGlass(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), 0, 0, 0)
            tag = "lock_glass_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 80
            progress = Prefs.lockGlass(this@MainActivity) - 20
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
        }, LinearLayout.LayoutParams(-1, dp(20)))
        appearanceCard.addView(TextView(this).apply {
            text = "Lock-screen UI scale: ${Prefs.lockScale(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), 0, 0, 0)
            tag = "lock_scale_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 30
            progress = Prefs.lockScale(this@MainActivity) - 85
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
        }, LinearLayout.LayoutParams(-1, dp(20)).apply { bottomMargin = dp(4) })
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
        appearanceCard.addView(guardianSwitch().apply {
            text = "Adaptive clock sizing"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.clockAdaptive(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setClockAdaptive(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Clock size: ${Prefs.clockScale(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(2), 0, 0)
            tag = "clock_scale_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 50
            progress = Prefs.clockScale(this@MainActivity) - 80
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
        }, LinearLayout.LayoutParams(-1, dp(24)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Show date under clock"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showDate(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowDate(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Weather & battery glass: ${Prefs.componentGlass(this@MainActivity, "top_info")}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(2), 0, 0)
            tag = "top_info_glass_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 80
            progress = Prefs.componentGlass(this@MainActivity, "top_info") - 20
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
        }, LinearLayout.LayoutParams(-1, dp(24)))
        appearanceCard.addView(TextView(this).apply {
            text = "Media player glass: ${Prefs.componentGlass(this@MainActivity, "media")}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(2), 0, 0)
            tag = "media_glass_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 80
            progress = Prefs.componentGlass(this@MainActivity, "media") - 20
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
        }, LinearLayout.LayoutParams(-1, dp(24)))
        appearanceCard.addView(TextView(this).apply {
            text = "Weather & battery content size: ${Prefs.topInfoSize(this@MainActivity)}%"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(2), 0, 0)
            tag = "top_info_size_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 40
            progress = Prefs.topInfoSize(this@MainActivity) - 80
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
        }, LinearLayout.LayoutParams(-1, dp(24)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Show weather in pill"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showWeather(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowWeather(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Show battery percentage"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showBatteryPercent(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowBatteryPercent(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Weather in Celsius (off: Fahrenheit)"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.celsius(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setCelsius(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        appearanceCard.addView(TextView(this).apply {
            text = "Primary weather location"
            textSize = 15f
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(6), dp(5), 0, dp(2))
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
        appearanceCard.addView(weatherLocation, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        addButton(appearanceCard, "Save primary weather location") {
            Prefs.setWeatherLocation(this, weatherLocation.text.toString())
            showD2Message(if (weatherLocation.text.isNullOrBlank()) "Weather set to automatic location" else "Primary weather location saved")
        }
        appearanceCard.addView(TextView(this).apply {
            text = "Leave Primary Location blank to use the device location. Enter a city, postcode, or city + country/region for global weather."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(6))
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
            setPadding(dp(6), dp(2), 0, 0)
            tag = "media_button_scale_label"
        })
        appearanceCard.addView(guardianSlider().apply {
            max = 40
            progress = Prefs.mediaButtonsScale(this@MainActivity) - 80
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
        }, LinearLayout.LayoutParams(-1, dp(24)))
        appearanceCard.addView(guardianSwitch().apply {
            text = "Show media player while D2 is locked"
            setTextColor(Appearance.text(this@MainActivity))
            isChecked = Prefs.showMedia(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setShowMedia(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
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
        val privacyCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(10), dp(7)); background = Appearance.glass(this@MainActivity, 24f, 28, true) }
        root.addView(privacyCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        privacyCard.addView(guardianSwitch().apply {
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
        }, LinearLayout.LayoutParams(-1, dp(30)))
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
        }, LinearLayout.LayoutParams(-1, dp(30)))
        privacyCard.addView(TextView(this).apply {
            text = "Public only shows text from apps that mark it public. All previews can show private messages before you authenticate with D2. Apps marked secret stay hidden."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(8), 0, dp(8), dp(12))
        })
        section(root, "FLOATING BAR")
        root.getChildAt(root.childCount - 1).tag = "floating_bar"
        val floatingCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(10), dp(7)); background = Appearance.glass(this@MainActivity, 24f, 28, true) }
        root.addView(floatingCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        addChoice(floatingCard, "Unlock gesture", listOf("Tap or slide", "Slide only", "Tap only"),
            listOf("tap_or_slide", "slide_only", "tap_only").indexOf(Prefs.floatingUnlockGesture(this)).coerceAtLeast(0)) {
            Prefs.setFloatingUnlockGesture(this, listOf("tap_or_slide", "slide_only", "tap_only")[it])
        }
        addChoice(floatingCard, "Floating Bar scroll behavior",
            listOf("Always visible", "Hide on scroll", "Exit after scrolling"),
            listOf("always_visible", "hide_on_scroll", "exit_after_scroll").indexOf(Prefs.floatingBarScrollBehavior(this)).coerceAtLeast(1)) {
            Prefs.setFloatingBarScrollBehavior(this, listOf("always_visible", "hide_on_scroll", "exit_after_scroll")[it])
            showD2Message("Floating Bar behavior updated")
            refreshAppearance()
        }
        floatingCard.addView(TextView(this).apply {
            text = "Always visible keeps the Guardian Dock pinned. Hide on scroll slides it away while scrolling down and restores it on scroll up. Exit after scrolling hides the dock for the rest of the current settings view once you begin scrolling."
            textSize = 12f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(6), 0, dp(6), dp(10))
        })
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
        section(root, "ACCESS CENTER")
        root.getChildAt(root.childCount - 1).tag = "main_settings"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_access)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    contentDescription=null
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text="Access Center"
                    textSize=18f
                    setTextColor(Appearance.text(this@MainActivity))
                })
            })
            addView(TextView(this@MainActivity).apply {
                text="Notifications  •  Media  •  Optional features"
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(4),0,dp(12))
            })
            fun accessAction(label:String, action:()->Unit)=TextView(this@MainActivity).apply {
                text=label
                textSize=14f
                gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,28,true)
                setPadding(dp(10),dp(13),dp(10),dp(13))
                isClickable=true
                isFocusable=true
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); action() }
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(accessAction("Notification & Media") {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
                addView(accessAction("Optional Access") {
                    requestRuntimePermissions()
                },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
            })
            addView(TextView(this@MainActivity).apply {
                text="Only permissions used by enabled Guardian features are required."
                textSize=11f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(10),0,0)
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        section(root, "WALLPAPER CENTER")
        root.getChildAt(root.childCount - 1).tag = "app_theme"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_wallpaper)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                    contentDescription=null
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text="Wallpaper Center"
                    textSize=18f
                    setTextColor(Appearance.text(this@MainActivity))
                })
            })
            addView(TextView(this@MainActivity).apply {
                text=(if(Prefs.wallpaper(this@MainActivity)!=null) "Lock screen set" else "Lock screen default")+
                    "  ·  "+(if(Prefs.appWallpaper(this@MainActivity)!=null) "D2 app set" else "D2 app default")
                textSize=12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0,dp(5),0,dp(10))
            })
            fun wallpaperAction(label:String, action:()->Unit)=TextView(this@MainActivity).apply {
                text=label
                textSize=15f
                gravity=Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,26,true)
                setPadding(dp(14),dp(10),dp(14),dp(10))
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); action() }
            }
            addView(wallpaperAction("Change lock-screen wallpaper") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type="image/*"
                },wallpaperPicker)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            addView(wallpaperAction("Change D2 app wallpaper") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type="image/*"
                },appWallpaperPicker)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="D2 app wallpaper dim · ${Prefs.appWallpaperDim(this@MainActivity)}%"
                textSize=13f
                setTextColor(Appearance.secondary(this@MainActivity))
                tag="d2_app_wallpaper_dim_label"
            })
            addView(guardianSlider().apply {
                max=90
                progress=Prefs.appWallpaperDim(this@MainActivity)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar:SeekBar?,value:Int,fromUser:Boolean) {
                        if(!fromUser) return
                        Prefs.setAppWallpaperDim(this@MainActivity,value)
                        root.findViewWithTag<TextView>("d2_app_wallpaper_dim_label")?.text="D2 app wallpaper dim · $value%"
                        if(value%10==0) seekBar?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
                    override fun onStopTrackingTouch(seekBar:SeekBar?) { refreshAppearance() }
                })
            },LinearLayout.LayoutParams(-1,dp(32)))
            val actions=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
            actions.addView(wallpaperAction("Reset D2 app") {
                Prefs.clearAppWallpaper(this@MainActivity)
                showD2Message("D2 app wallpaper reset")
                refreshAppearance()
            },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(4) })
            actions.addView(wallpaperAction("Preview") {
                startActivity(Intent(this@MainActivity,LockScreenActivity::class.java).putExtra("preview",true))
            }.apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_guardian_preview,0,0,0)
                compoundDrawableTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                compoundDrawablePadding=dp(5)
            },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(4) })
            addView(actions)
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        section(root, "EXPERIMENTAL LAB")
        root.getChildAt(root.childCount - 1).tag = "advanced_settings"
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(9),dp(12),dp(9))
            background=Appearance.glass(this@MainActivity,24f,30,true)
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_guardian_advanced)
                    imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity)); contentDescription=null
                },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
                addView(TextView(this@MainActivity).apply { text="Experimental Lab"; textSize=18f; setTextColor(Appearance.text(this@MainActivity)) })
            })
            val labBridge=app.d2lock.lockscreen.KeyguardSignalReceiver.systemHealth(this@MainActivity)
            val labPipeline=app.d2lock.lockscreen.KeyguardSignalReceiver.pipelineMetrics(this@MainActivity)
            addView(TextView(this@MainActivity).apply {
                text="Framework-assisted experiments  •  ${labBridge.status}\nPipeline: ${labPipeline.timeline.size} recent stages"
                textSize=11f; setTextColor(if(labBridge.status=="CONNECTED") Color.rgb(102,220,132) else Appearance.secondary(this@MainActivity))
                setPadding(0,dp(5),0,dp(8))
            })
            fun labTile(title:String, status:String, checked:()->Boolean, changed:(Boolean)->Unit)=
                LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(7),dp(7),dp(7))
                    background=Appearance.glass(this@MainActivity,20f,22,true)
                    addView(TextView(this@MainActivity).apply {
                        text=title; textSize=11f; maxLines=2; setTextColor(Appearance.text(this@MainActivity))
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text=status; textSize=8f; setTextColor(Appearance.secondary(this@MainActivity))
                        },LinearLayout.LayoutParams(0,-2,1f))
                        addView(guardianSwitch().apply {
                            isChecked=checked(); scaleX=.78f; scaleY=.78f
                            setPadding(0, 0, dp(4), 0)
                            setOnCheckedChangeListener { _,v -> changed(v) }
                        },LinearLayout.LayoutParams(dp(58),dp(48)))
                    })
                }
            fun labGrid(items:List<LinearLayout>) {
                items.chunked(2).forEach { pair ->
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.HORIZONTAL
                        pair.forEachIndexed { i,v -> addView(v,LinearLayout.LayoutParams(0,-2,1f).apply { if(i==0) rightMargin=dp(3) else leftMargin=dp(3) }) }
                        if(pair.size==1) addView(android.view.View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
                    },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                }
            }
            labGrid(listOf(
                labTile("Context-Aware Glass","FRAMEWORK ASSISTED",{ Prefs.experimentalAdaptiveGlass(this@MainActivity) }) { Prefs.setExperimentalAdaptiveGlass(this@MainActivity,it) },
                labTile("Guardian State Accent","FRAMEWORK ASSISTED",{ Prefs.experimentalDynamicAccent(this@MainActivity) }) { Prefs.setExperimentalDynamicAccent(this@MainActivity,it); refreshAppearance() },
                labTile("Event Haptics","PIPELINE EVENTS",{ Prefs.experimentalEnhancedHaptics(this@MainActivity) }) { Prefs.setExperimentalEnhancedHaptics(this@MainActivity,it) },
                labTile("Synchronized Motion","KEYGUARD / BIOMETRIC",{ Prefs.experimentalLockMotion(this@MainActivity) }) { Prefs.setExperimentalLockMotion(this@MainActivity,it) },
                labTile("State-Aware Stack","AUTH / LOCK STATE",{ Prefs.experimentalAdaptiveNotifications(this@MainActivity) }) { Prefs.setExperimentalAdaptiveNotifications(this@MainActivity,it) },
                labTile("Adaptive Lock Transition","WAKE → D2 HEALTHY",{ Prefs.experimentalAdaptiveTransition(this@MainActivity) }) { Prefs.setExperimentalAdaptiveTransition(this@MainActivity,it) },
                labTile("Framework Timing","LEARNING TIMING",{ Prefs.experimentalFrameworkTiming(this@MainActivity) }) { Prefs.setExperimentalFrameworkTiming(this@MainActivity,it) }
            ))
            addView(TextView(this@MainActivity).apply {
                text="Experiments consume Guardian/System Server timing and state only. Android remains authoritative for credential and biometric verification."
                textSize=10f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(dp(3),dp(4),dp(3),dp(7))
            })
            addView(TextView(this@MainActivity).apply {
                text="Guardian Recovery"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,26,true); setPadding(dp(14),dp(9),dp(14),dp(9))
                setOnClickListener { startActivity(Intent(this@MainActivity,app.d2lock.ui.SeslPreviewActivity::class.java)) }
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        section(root, "GUARDIAN TERMINAL")
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(14))
            background=Appearance.glass(this@MainActivity,30f,38,true)
            addView(TextView(this@MainActivity).apply { text="Framework Command Center"; textSize=18f; setTextColor(Appearance.text(this@MainActivity)) })
            addView(TextView(this@MainActivity).apply {
                text="ROOT  ·  SHIZUKU  ·  SYSTEM SERVER  ·  ZYGOTE\nIndependent sessions, live bridge health, command history and framework diagnostics."
                textSize=12f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(5),0,dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text="Open Guardian Terminal"; textSize=14f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                background=Appearance.glass(this@MainActivity,22f,32,true); setPadding(dp(14),dp(11),dp(14),dp(11))
                setOnClickListener { startActivity(Intent(this@MainActivity,app.d2lock.terminal.GuardianTerminalActivity::class.java)) }
            })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        section(root, "XPOSED INTEGRATION")
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = Appearance.glass(this@MainActivity, 30f, 38, true)

            addView(TextView(this@MainActivity).apply {
                text = "Guardian Xposed Integration"
                textSize = 18f
                setTextColor(Appearance.text(this@MainActivity))
            })
            val systemHealth = app.d2lock.lockscreen.KeyguardSignalReceiver.systemHealth(this@MainActivity)
            val bridgeHealth = app.d2lock.lockscreen.KeyguardSignalReceiver.bridgeHealth(this@MainActivity)
            addView(TextView(this@MainActivity).apply {
                val age = systemHealth.ageMs?.let { "${it / 1000}s ago" } ?: "waiting for first event"
                val event = systemHealth.event ?: "none"
                val method = systemHealth.method ?: "none"
                text = "System Server bridge  •  ${systemHealth.status}\nLast event: $event • $age\nHook: $method"
                textSize = 14f
                setTextColor(
                    when (systemHealth.status) {
                        "CONNECTED" -> Color.rgb(102, 220, 132)
                        "STALE" -> Color.rgb(245, 184, 72)
                        else -> Appearance.secondary(this@MainActivity)
                    }
                )
                setPadding(0, dp(7), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                val age = bridgeHealth.ageMs?.let { "${it / 1000}s ago" } ?: "no heartbeat yet"
                text = "SystemUI bridge  •  ${bridgeHealth.status}\nHeartbeat: $age"
                textSize = 12f
                setTextColor(
                    when (bridgeHealth.status) {
                        "CONNECTED" -> Color.rgb(102, 220, 132)
                        "STALE" -> Color.rgb(245, 184, 72)
                        else -> Appearance.secondary(this@MainActivity)
                    }
                )
                setPadding(0, dp(2), 0, dp(7))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Current Android 17 path: ActivityManagerService.systemReady sends BOOT_READY after Android returns from systemReady. D2 now uses that early signal for the earliest safe rooted Guardian launch; LOCKED_BOOT_COMPLETED remains the recovery fallback. SystemUI heartbeat is separate and is not required for the System Server boot handoff."
                textSize = 12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0, dp(5), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Behavior controls below are experimental unless individually verified. Safe diagnostics can remain enabled while testing boot timing."
                textSize = 11f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(0, dp(2), 0, dp(8))
            })

            fun masterXposedToggle(title: String, checked: () -> Boolean, changed: (Boolean) -> Unit) {
                addView(guardianSwitch().apply {
                    text = title; textSize = 15f; setTextColor(Appearance.text(this@MainActivity))
                    isChecked = checked(); setOnCheckedChangeListener { _, value -> changed(value) }
                }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(4) })
            }
            fun compactXposedToggle(title: String, checked: () -> Boolean, changed: (Boolean) -> Unit): LinearLayout =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(5), dp(7), dp(5)); background = Appearance.glass(this@MainActivity, 20f, 22, true)
                    addView(TextView(this@MainActivity).apply {
                        text = title; textSize = 11f; maxLines = 2; setTextColor(Appearance.text(this@MainActivity))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(guardianSwitch().apply {
                        // These stored switches have no behavioral hook implementation yet.
                        isChecked = false; isEnabled = false; contentDescription = "$title: not implemented"
                        scaleX = .82f; scaleY = .82f
                        setPadding(0, 0, dp(4), 0)
                        setOnCheckedChangeListener { _, value -> changed(value) }
                    }, LinearLayout.LayoutParams(dp(60), dp(48)))
                }
            fun compactGrid(items: List<Triple<String, () -> Boolean, (Boolean) -> Unit>>) {
                items.chunked(2).forEach { pair ->
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEachIndexed { index, item ->
                            addView(compactXposedToggle(item.first, item.second, item.third),
                                LinearLayout.LayoutParams(0, -2, 1f).apply {
                                    if(index == 0) rightMargin = dp(3) else leftMargin = dp(3)
                                })
                        }
                        if(pair.size == 1) addView(android.view.View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
            }

            masterXposedToggle("Accept framework signals", { Prefs.xposedMaster(this@MainActivity) }) { Prefs.setXposedMaster(this@MainActivity, it) }
            addView(compactXposedToggle("Safe diagnostics only", { Prefs.xposedSafeDiagnostics(this@MainActivity) }) { Prefs.setXposedSafeDiagnostics(this@MainActivity, it) },
                LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })

            compactGrid(listOf(
                Triple("Screen / wake", { Prefs.xposedScreenAwareness(this@MainActivity) }, { v:Boolean -> Prefs.setXposedScreenAwareness(this@MainActivity,v) }),
                Triple("Immediate relock", { Prefs.xposedImmediateRelock(this@MainActivity) }, { v:Boolean -> Prefs.setXposedImmediateRelock(this@MainActivity,v) }),
                Triple("Home / Recents", { Prefs.xposedHomeRecents(this@MainActivity) }, { v:Boolean -> Prefs.setXposedHomeRecents(this@MainActivity,v) }),
                Triple("Gesture coordination", { Prefs.xposedGestureCoordination(this@MainActivity) }, { v:Boolean -> Prefs.setXposedGestureCoordination(this@MainActivity,v) }),
                Triple("Notifications", { Prefs.xposedNotifications(this@MainActivity) }, { v:Boolean -> Prefs.setXposedNotifications(this@MainActivity,v) }),
                Triple("Media", { Prefs.xposedMedia(this@MainActivity) }, { v:Boolean -> Prefs.setXposedMedia(this@MainActivity,v) }),
                Triple("Call state", { Prefs.xposedCalls(this@MainActivity) }, { v:Boolean -> Prefs.setXposedCalls(this@MainActivity,v) }),
                Triple("SystemUI recovery", { Prefs.xposedSystemUiRecovery(this@MainActivity) }, { v:Boolean -> Prefs.setXposedSystemUiRecovery(this@MainActivity,v) }),
                Triple("QS / status guard", { Prefs.xposedBarsGuard(this@MainActivity) }, { v:Boolean -> Prefs.setXposedBarsGuard(this@MainActivity,v) }),
                Triple("Native lifecycle", { Prefs.xposedNativeLifecycle(this@MainActivity) }, { v:Boolean -> Prefs.setXposedNativeLifecycle(this@MainActivity,v) }),
                Triple("Guardian fallback", { Prefs.xposedAutomaticFallback(this@MainActivity) }, { v:Boolean -> Prefs.setXposedAutomaticFallback(this@MainActivity,v) })
            ))

            addView(TextView(this@MainActivity).apply {
                text = "Galaxy Island • Xposed"; textSize = 14f; setTextColor(Appearance.text(this@MainActivity))
                background = Appearance.glass(this@MainActivity, 22f, 26, true); setPadding(dp(12), dp(8), dp(12), dp(8))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
            compactGrid(listOf(
                Triple("SystemUI integration", { Prefs.xposedGalaxyIsland(this@MainActivity) }, { v:Boolean -> Prefs.setXposedGalaxyIsland(this@MainActivity,v) }),
                Triple("Native notifications", { Prefs.xposedIslandNotifications(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandNotifications(this@MainActivity,v) }),
                Triple("Native media", { Prefs.xposedIslandMedia(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandMedia(this@MainActivity,v) }),
                Triple("Charging / battery", { Prefs.xposedIslandCharging(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandCharging(this@MainActivity,v) }),
                Triple("Call events", { Prefs.xposedIslandCalls(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandCalls(this@MainActivity,v) }),
                Triple("Screen state", { Prefs.xposedIslandScreenState(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandScreenState(this@MainActivity,v) }),
                Triple("SystemUI positioning", { Prefs.xposedIslandPositioning(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandPositioning(this@MainActivity,v) }),
                Triple("Guardian sync", { Prefs.xposedIslandGuardianSync(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandGuardianSync(this@MainActivity,v) }),
                Triple("Fallback bridge", { Prefs.xposedIslandFallback(this@MainActivity) }, { v:Boolean -> Prefs.setXposedIslandFallback(this@MainActivity,v) })
            ))

            addView(TextView(this@MainActivity).apply {
                text = "In development: the additional framework and Galaxy Island controls shown above are placeholders and remain disabled until their individual hooks are implemented and tested. Development is deferred to a later update. Current boot/wake observers, D2 SRP and power-menu hooks run separately; these switches do not control them. The separate KernelSU bridge ZIP remains optional."
                textSize = 12f
                setTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(4), dp(8), dp(4), 0)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        section(root, "LOCK-SCREEN PROFILES")
        root.getChildAt(root.childCount - 1).tag = "advanced_settings"
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
            text = "Quick presets for clock, glass, wallpaper, notifications, media and Floating Bar."
            textSize = 13f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
        addButton(root, "Profile Manager") {
            val names = Prefs.customProfileNames(this)
            val options = mutableListOf("Save current as new profile")
            if (names.isNotEmpty()) {
                options += "Load custom profile"
                options += "Delete custom profile"
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Profile Manager")
                .setItems(options.toTypedArray()) { _, which ->
                    when (options[which]) {
                        "Save current as new profile" -> {
                            val input = android.widget.EditText(this).apply {
                                hint = "Profile name"; setSingleLine()
                                setTextColor(Appearance.text(this@MainActivity))
                                setHintTextColor(Appearance.secondary(this@MainActivity))
                                setPadding(dp(16), dp(12), dp(16), dp(12))
                                background = Appearance.glass(this@MainActivity, 24f, 34, true)
                            }
                            val saveDialog = AlertDialog.Builder(this)
                                .setTitle("Save custom profile").setView(input)
                                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
                            saveDialog.setOnShowListener {
                                saveDialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                                saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                                saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                                    val name = input.text.toString().trim()
                                    if (name.isBlank()) showD2Message("Enter a profile name") else {
                                        runCatching { Prefs.saveCustomProfile(this@MainActivity, name) }
                                            .onSuccess { saveDialog.dismiss(); showD2Message("$name profile saved"); refreshAppearance() }
                                            .onFailure { showD2Message("Profile could not be saved: ${it.message}") }
                                    }
                                }
                            }
                            saveDialog.show()
                        }
                        "Load custom profile" -> AlertDialog.Builder(this).setTitle("Load profile")
                            .setItems(names.toTypedArray()) { _, i ->
                                if (Prefs.applyCustomProfile(this, names[i])) {
                                    showD2Message("${names[i]} profile applied"); refreshAppearance()
                                } else showD2Message("Profile is invalid or could not be restored")
                            }.setNegativeButton("Cancel", null).create().also {
                                it.setOnShowListener { _ -> it.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true)) }; it.show()
                            }
                        "Delete custom profile" -> AlertDialog.Builder(this).setTitle("Delete profile")
                            .setItems(names.toTypedArray()) { _, i ->
                                Prefs.deleteCustomProfile(this, names[i]); showD2Message("${names[i]} profile deleted"); refreshAppearance()
                            }.setNegativeButton("Cancel", null).create().also {
                                it.setOnShowListener { _ -> it.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true)) }; it.show()
                            }
                    }
                }.setNegativeButton("Done", null).create()
            dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true)) }
            dialog.show()
        }

        section(root, "ABOUT & DATA")
        val backupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun backupBubble(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Appearance.text(this@MainActivity))
            background = Appearance.glass(this@MainActivity, 26f, 34, true)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setOnClickListener { runCatching { action() }.onFailure { showD2Message("Backup could not be created: ${it.message}") } }
        }
        backupRow.addView(backupBubble("Copy backup") {
            val backup = Prefs.exportSettings(this)
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("D2 settings backup", backup))
            showD2Message("D2 settings backup copied")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        backupRow.addView(backupBubble("Restore backup") {
            val input = android.widget.EditText(this).apply {
                hint = "Paste D2 settings backup"; minLines = 5; maxLines = 10
                setTextColor(Appearance.text(this@MainActivity)); setHintTextColor(Appearance.secondary(this@MainActivity))
                setPadding(dp(16), dp(12), dp(16), dp(12)); background = Appearance.glass(this@MainActivity, 24f, 34, true)
            }
            val restoreDialog = AlertDialog.Builder(this).setTitle("Restore D2 settings")
                .setMessage("Appearance and lock-screen preferences will be restored. Root, kiosk, ADB recovery, Shizuku state, and D2 enabled state are intentionally not imported.")
                .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Restore", null).create()
            restoreDialog.setOnShowListener {
                restoreDialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                restoreDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
                restoreDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val raw = input.text.toString()
                    val button = restoreDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    button.isEnabled = false
                    Thread {
                        val result = runCatching { Prefs.importSettings(this@MainActivity, raw) }
                        runOnUiThread {
                            if (isDestroyed || isFinishing || !authorized) return@runOnUiThread
                            button.isEnabled = true
                            result.onSuccess { count -> restoreDialog.dismiss(); showD2Message("Restored $count D2 settings"); refreshAppearance() }
                                .onFailure { showD2Message("Backup could not be restored: ${it.message}") }
                        }
                    }.start()
                }
            }
            restoreDialog.show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        root.addView(backupRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        addButton(root, "App Updates") {
            showAppUpdates()
        }

        addButton(root, "Legal, privacy & licenses") {
            val notice = assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            val agreement = assets.open("D2_USER_AGREEMENT.txt").bufferedReader().use { it.readText() }
            val dialogBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(14))
                background = Appearance.glass(this@MainActivity, 30f, 58, true)

                addView(TextView(this@MainActivity).apply {
                    text = "KIOSK D2 BOOT GUARDIAN"
                    textSize = 12f
                    letterSpacing = .08f
                    setTextColor(Appearance.secondary(this@MainActivity))
                    setPadding(dp(4), dp(2), dp(4), dp(4))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Legal & Privacy"
                    textSize = 22f
                    setTextColor(Appearance.text(this@MainActivity))
                    setPadding(dp(4), 0, dp(4), dp(10))
                })

                val tabs = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                val legalText = TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(Appearance.text(this@MainActivity))
                    setLineSpacing(0f, 1.14f)
                    setPadding(dp(16), dp(14), dp(16), dp(18))
                    setTextIsSelectable(true)
                }
                fun showLegal() { legalText.text = agreement }
                fun showLicenses() {
                    legalText.text = "THIRD-PARTY LICENSES & CREDITS\n\nWeather data and geocoding: Open-Meteo.\n\n$notice"
                }
                fun tab(title: String, action: () -> Unit) = TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Appearance.text(this@MainActivity))
                    background = Appearance.glass(this@MainActivity, 20f, 28, true)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener { action() }
                }
                tabs.addView(tab("Agreement & Privacy") { showLegal() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                tabs.addView(tab("Licenses & Credits") { showLicenses() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
                addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                val scroll = ScrollView(this@MainActivity).apply {
                    isVerticalScrollBarEnabled = true
                    background = Appearance.glass(this@MainActivity, 26f, 28, true)
                    addView(legalText)
                }
                addView(scroll, LinearLayout.LayoutParams(-1, dp(500)))
                showLegal()
            }
            val dialog = AlertDialog.Builder(this)
                .setView(dialogBody)
                .setPositiveButton("Done", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 34f, 78, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(Appearance.accent(this@MainActivity))
                    textSize = 15f
                }
            }
            dialog.show()
        }

        addButton(root, "About Kiosk D2 Boot Guardian") {
            val info = TextView(this).apply {
                text = "Kiosk D2 Boot Guardian is a root-aware Samsung lock-screen and kiosk protection project built around D2 PIN/pattern authentication, Guardian relock protection, KernelSU Root Mode, Shizuku, LSPosed integration, and Galaxy Island coordination. The verified Android 17 baseline keeps D2 normally installed under /data/app, enables KernelSU Root Mode in System Integrations, and uses a minimal LSPosed system_server hook that signals Guardian only after ActivityManagerService reaches systemReady. Five consecutive reboots were validated on the SM-S948U1 test device with the separate KernelSU paired bridge disabled.\n\nFingerprint login uses Android BiometricPrompt for the D2 app and lock surface, with D2 PIN recovery. Keep Samsung PIN and fingerprint enrollment, and turn Samsung Fingerprint unlock OFF. Samsung may require its separate PIN after reboot or strong-authentication events. Framework telemetry is observational and does not authorize an unlock.\n\nRoot, recovery and reboot remain privileged bypass paths. D2 cannot repair firmware or guarantee prevention of download-mode errors."
                textSize = 14f
                setTextColor(Appearance.text(this@MainActivity))
                setLineSpacing(0f, 1.14f)
                setPadding(dp(18), dp(14), dp(18), dp(18))
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("About Kiosk D2 Boot Guardian")
                .setView(info)
                .setPositiveButton("Done", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 30f, 76, true))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Appearance.accent(this@MainActivity))
            }
            dialog.show()
        }
        return settingsHost(root)
    }

    private fun showAppUpdates() {
        val installed = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "current"
        val choices = arrayOf("Check for updates", "Release channel · " + Prefs.updateChannel(this).replaceFirstChar { it.uppercase() })
        val dialog = AlertDialog.Builder(this)
            .setTitle("App Updates")
            .setMessage("Installed: $installed\n\nD2 checks published GitHub Releases from SysopBP/SamsungLockD2-Public. Updates are never silently installed.")
            .setItems(choices) { _, which ->
                if (which == 0) checkForAppUpdate()
                else {
                    val channels = arrayOf("Stable", "Preview")
                    val current = if (Prefs.updateChannel(this) == "stable") 0 else 1
                    AlertDialog.Builder(this).setTitle("Release channel")
                        .setSingleChoiceItems(channels, current) { channelDialog, selected ->
                            Prefs.setUpdateChannel(this, if (selected == 0) "stable" else "preview")
                            channelDialog.dismiss()
                            showD2Message("Update channel: ${channels[selected]}")
                        }.setNegativeButton("Cancel", null).show()
                }
            }
            .setNegativeButton("Done", null)
            .create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this, 30f, 76, true)) }
        dialog.show()
    }

    private fun checkForAppUpdate() {
        showD2Message("Checking GitHub Releases…")
        Thread {
            val result = app.d2lock.update.GitHubUpdateChecker.check(this)
            runOnUiThread {
                result.onFailure { showD2Message("Update check failed: ${it.message ?: "network error"}") }
                    .onSuccess { release ->
                        if (release == null) {
                            AlertDialog.Builder(this).setTitle("D2 is up to date")
                                .setMessage("No newer ${Prefs.updateChannel(this)} release with an APK was found.")
                                .setPositiveButton("Done", null).show()
                        } else {
                            val notes = release.body.ifBlank { "No release notes were provided." }.take(3500)
                            val label = if (release.prerelease) "Preview" else "Stable"
                            val updateDialog = AlertDialog.Builder(this)
                                .setTitle("Update available · ${release.tag}")
                                .setMessage("$label release\nPublished: ${release.publishedAt}\n\n$notes")
                                .setPositiveButton("Download Update") { _, _ ->
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl))) }
                                        .onFailure { showD2Message("Could not open the release APK") }
                                }
                                .setNeutralButton("Skip this version") { _, _ -> Prefs.setSkippedUpdateTag(this, release.tag) }
                                .setNegativeButton("Later", null)
                                .create()
                            updateDialog.setOnShowListener { updateDialog.window?.setBackgroundDrawable(Appearance.glass(this, 30f, 76, true)) }
                            updateDialog.show()
                        }
                    }
            }
        }.start()
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

    private inner class GuardianSwitch(context: android.content.Context) : Switch(context) {
        private val glassPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val glassStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        init {
            showText = false
            minimumHeight = dp(48)
            // Reserve a dedicated lane for the custom control so long labels never
            // render underneath the Guardian toggle.
            setPadding(paddingLeft, paddingTop, dp(72), paddingBottom)
            // Keep Switch's text/listener API for all existing settings, but make its
            // Samsung-rendered switch assets invisible. Guardian draws the control itself.
            trackDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            thumbDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            // Let TextView/Switch render the setting label first; native track/thumb are transparent.
            super.onDraw(canvas)
            val trackW = dp(52).toFloat()
            val trackH = dp(30).toFloat()
            val right = width - dp(4).toFloat()
            val left = right - trackW
            val top = (height - trackH) / 2f
            val bottom = top + trackH
            val radius = trackH / 2f
            val accent = Appearance.accent(this@MainActivity)
            glassPaint.style = android.graphics.Paint.Style.FILL
            glassPaint.color = if (isChecked) {
                (accent and 0x00ffffff) or 0x72000000
            } else if (Appearance.dark(this@MainActivity)) 0x38ffffff else 0x2c000000
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, glassPaint)

            glassStroke.color = if (isChecked) 0xb0ffffff.toInt() else 0x70ffffff
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, glassStroke)

            val thumbR = dp(11).toFloat()
            val cx = if (isChecked) right - radius else left + radius
            val cy = (top + bottom) / 2f
            glassPaint.color = if (isChecked) 0xf4ffffff.toInt() else 0xc8ffffff.toInt()
            canvas.drawCircle(cx, cy, thumbR, glassPaint)
            glassStroke.color = 0x90ffffff.toInt()
            canvas.drawCircle(cx, cy, thumbR, glassStroke)
        }
    }

    private fun guardianSwitch(): Switch = GuardianSwitch(this)

    private inner class GuardianSlider(context: android.content.Context) : SeekBar(context) {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        init {
            // D2 owns the visuals: hide stock/Theme Park assets, retain native SeekBar behavior.
            progressDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            thumb = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            splitTrack = false
            minimumHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            val cy = height / 2f
            // Slim SESL/One UI-like geometry with Guardian glass materials.
            val railH = dp(8).toFloat()
            val radius = railH / 2f
            val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
            val x = left + (right - left) * fraction
            val accent = Appearance.accent(this@MainActivity)

            fill.style = android.graphics.Paint.Style.FILL
            fill.color = if (Appearance.dark(this@MainActivity)) 0x2effffff else 0x22000000
            canvas.drawRoundRect(left, cy - railH / 2f, right, cy + railH / 2f, radius, radius, fill)

            outline.color = if (Appearance.dark(this@MainActivity)) 0x50ffffff else 0x38000000
            canvas.drawRoundRect(left, cy - railH / 2f, right, cy + railH / 2f, radius, radius, outline)

            if (x > left) {
                fill.color = (accent and 0x00ffffff) or 0xb8000000.toInt()
                canvas.drawRoundRect(left, cy - railH / 2f, x, cy + railH / 2f, radius, radius, fill)
            }

            // Compact SESL-like thumb, rendered as translucent Guardian glass.
            val thumbR = dp(9).toFloat()
            fill.color = (accent and 0x00ffffff) or 0xe6000000.toInt()
            canvas.drawCircle(x, cy, thumbR, fill)
            outline.color = 0x99ffffff.toInt()
            canvas.drawCircle(x, cy, thumbR, outline)
            fill.color = 0x42ffffff
            canvas.drawCircle(x - dp(3), cy - dp(3), dp(2).toFloat(), fill)
        }
    }

    private fun guardianSlider(): GuardianSlider = GuardianSlider(this)

    private fun flashSettingsTarget(view: android.view.View) {
        // Highlight only the exact tagged destination. The previous implementation
        // promoted the target to its parent container, so jumping to one setting
        // could make an entire Center (or several sibling cards) appear to flash.
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val pulseTarget = view
        val originalAlpha = pulseTarget.alpha
        val originalScaleX = pulseTarget.scaleX
        val originalScaleY = pulseTarget.scaleY
        pulseTarget.animate().cancel()
        pulseTarget.animate()
            .alpha(0.72f)
            .scaleX(originalScaleX * 0.985f)
            .scaleY(originalScaleY * 0.985f)
            .setDuration(90)
            .withEndAction {
                pulseTarget.animate()
                    .alpha(originalAlpha)
                    .scaleX(originalScaleX)
                    .scaleY(originalScaleY)
                    .setDuration(190)
                    .start()
            }.start()
    }

    private fun shizukuAuthorized() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun biometricReady() = getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricAvailability(): String = when(getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Available"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Enrollment required"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Sensor temporarily unavailable"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No strong biometric hardware"
        else -> "Unavailable; complete Samsung PIN unlock and retry"
    }

    private fun checkTime(timestamp: Long) = if(timestamp <= 0L) "Not checked" else
        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp))

    private fun guidedPrefs() = getSharedPreferences("d2_guided_tests", MODE_PRIVATE)
    private fun recordGuidedResult(key: String, result: String) {
        guidedPrefs().edit().putString(key, result).putLong(key+"_time", System.currentTimeMillis()).apply()
    }
    private fun resolveShizuku() {
        if(!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            GuardianDialogs.showInfo(this, "Start Shizuku", "Start Shizuku or TokenX, return to D2, then recheck. D2 cannot start an unavailable binder from this screen.")
        } else if(!shizukuAuthorized()) Shizuku.requestPermission(shizukuRequestCode)
        else GuardianDialogs.showInfo(this, "Shizuku authorized", "The service is connected and D2 permission is granted.")
    }

    private fun showReadiness() {
        if(!authorized || appStopped) return
        readinessDialog?.dismiss()
        val checked = System.currentTimeMillis()
        val panel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(18)) }
        fun row(title:String, detail:String, action:(()->Unit)? = null) {
            panel.addView(TextView(this).apply {
                text="$title\n$detail"; textSize=14f; minimumHeight=dp(56)
                setTextColor(Appearance.text(this@MainActivity)); setPadding(dp(12),dp(10),dp(12),dp(10))
                background=Appearance.glass(this@MainActivity,22f,30,true)
                if(action!=null) { isFocusable=true; setOnClickListener { readinessDialog?.dismiss(); action() } }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        row("Guardian readiness", "Checked: ${checkTime(checked)}. Configuration and observed results are separate.")
        row("D2 PIN · ${if(PinStore(this).configured()) "Configured" else "Missing"}", "Tap to verify PIN recovery") {
            pinDialog=PinUi.show(this, success={ recordGuidedResult("pin", "Verified D2 PIN"); showReadiness() })
        }
        val fp=KeyguardSignalReceiver.fingerprintHealth(this)
        row("Fingerprint · ${biometricAvailability()}", "Selected: ${Prefs.unlockMethod(this)}. Last event: ${fp.event ?: "None this boot"}. Tap for sensor test.") { runGuardianBiometricTest() }
        row("Shizuku · ${if(shizukuAuthorized()) "Authorized" else "Needs attention"}", "Tap to connect or authorize") { resolveShizuku() }
        row("Root · ${if(RootManager.isAvailable()) "Available" else "Unavailable"}", "Tap to refresh root availability; grant D2 in KernelSU if required") {
            RootManager.refreshAsync(); Toast.makeText(this,"Root check requested; tap Refresh shortly",Toast.LENGTH_LONG).show()
        }
        val server=KeyguardSignalReceiver.systemHealth(this)
        row("System Server · ${server.status}", "Last event: ${server.event ?: "None"}. Tap for diagnostics") { showSystemServerDashboard() }
        val srp=app.d2lock.root.DmcRecovery.status(this)
        row("D2 SRP · ${if(srp.targetsMet) "Targets verified" else "Not verified"}", "${srp.detail} · ${checkTime(srp.checkedAt)}. Tap for status/check.") { showDmcStatus() }
        for(key in listOf("pin","sensor","wake","relock")) {
            val result=guidedPrefs().getString(key,null)
            row("${key.replaceFirstChar { it.uppercase() }} test history", if(result==null) "Not tested" else "$result · ${checkTime(guidedPrefs().getLong(key+"_time",0L))}")
        }
        row("Test D2 unlock", "Open the real D2 lock surface; verify fingerprint and PIN fallback. This does not enable kiosk.") {
            startActivity(Intent(this,LockScreenActivity::class.java))
        }
        row("Wake / relock checklist", "Turn the screen off, wake it, unlock D2, and repeat. Record your observed outcome here after returning.") {
            GuardianDialogs.showChoices(this,"Observed wake/relock result",arrayOf("Wake and relock worked","Wake failed","Relock failed"),-1) { d,which ->
                recordGuidedResult("wake",if(which==1) "User reported failure" else "User confirmed wake")
                recordGuidedResult("relock",if(which==2) "User reported failure" else if(which==0) "User confirmed relock" else "Not confirmed")
                d.dismiss(); showReadiness()
            }
        }
        val scroll=ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this).setTitle("Readiness & guided tests").setView(scroll)
            .setNegativeButton("Close",null).setPositiveButton("Refresh") { _,_ -> showReadiness() }
            .create().also { d -> readinessDialog=d; d.setOnShowListener { d.window?.setBackgroundDrawable(Appearance.glass(this,32f,82,true)); d.window?.setLayout(-1,(resources.displayMetrics.heightPixels*.82f).toInt()) }; d.show() }
    }

    private fun showGuardianCoreHealth() {
        val rootAvailable=RootManager.isAvailable(); val shizukuAvailable=shizukuAuthorized()
        val authReady=PinStore(this).configured(); val wakeReady=Prefs.enabled(this)
        val islandPaired=app.d2lock.bridge.IslandBridge.enabled(this); val islandSync=!islandPaired || Prefs.xposedIslandGuardianSync(this)
        val healthy=authReady && wakeReady && (!Prefs.rootMode(this) || rootAvailable) && (!Prefs.shizukuEnabled(this) || shizukuAvailable)  && islandSync
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(16)); background=Appearance.glass(this@MainActivity,32f,64,true)
            addView(TextView(this@MainActivity).apply { text=if(healthy) "◆  GUARDIAN CONFIGURED" else "◆  GUARDIAN ATTENTION"; textSize=13f; setTextColor(if(healthy) Color.rgb(102,220,132) else Appearance.accent(this@MainActivity)) })
            addView(TextView(this@MainActivity).apply { text="Guardian Core"; textSize=24f; setTextColor(Appearance.text(this@MainActivity)); setPadding(0,dp(3),0,dp(12)) })
            fun status(title:String,detail:String,ok:Boolean) { addView(TextView(this@MainActivity).apply { text=(if(ok) "●  " else "○  ")+title+"\n    "+detail; textSize=14f; setTextColor(if(ok) Appearance.text(this@MainActivity) else Appearance.accent(this@MainActivity)); setPadding(dp(13),dp(9),dp(13),dp(9)); background=Appearance.glass(this@MainActivity,24f,28,true) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) }) }
            status("D2 authentication",if(authReady) "Credential configured" else "Create a D2 PIN",authReady)
            status("Lock & wake",if(wakeReady) "Guardian armed for screen wake" else "Show D2 on wake is off",wakeReady)
            status("Root / KernelSU",if(rootAvailable) "Root shell available" else "Root unavailable",!Prefs.rootMode(this@MainActivity)||rootAvailable)
            status("Shizuku",if(shizukuAvailable) "Binder connected" else "Binder unavailable",!Prefs.shizukuEnabled(this@MainActivity)||shizukuAvailable)
            status("LSPosed / Xposed",if(Prefs.xposedMaster(this@MainActivity)) "SystemUI integration configured" else "Optional integration off",!Prefs.xposedMaster(this@MainActivity)||Prefs.xposedAutomaticFallback(this@MainActivity))
            status("Galaxy Island",if(islandPaired) "Paired · Guardian sync "+if(islandSync) "ready" else "needs attention" else "Not paired · optional",islandSync)
            status("Kiosk protection",if(Prefs.kiosk(this@MainActivity)) "Kiosk enabled" else "Kiosk currently off",!Prefs.kiosk(this@MainActivity)||rootAvailable)
            status("Recovery path",if(rootAvailable||Prefs.adbRecovery(this@MainActivity)) "Root or ADB recovery available" else "Verify recovery before kiosk use",rootAvailable||Prefs.adbRecovery(this@MainActivity))
            val fp = KeyguardSignalReceiver.fingerprintHealth(this@MainActivity)
            status("Fingerprint", biometricAvailability()+" · Last event: "+(fp.event ?: "None this boot"), biometricReady())
            val srp = app.d2lock.root.DmcRecovery.status(this@MainActivity)
            status("D2 SRP", (if(srp.targetsMet) "Targets verified" else "Needs verification")+" · "+checkTime(srp.checkedAt), srp.targetsMet)
            addView(TextView(this@MainActivity).apply {
                text="Readiness & guided tests  ›"; textSize=15f; minimumHeight=dp(48)
                setTextColor(Appearance.text(this@MainActivity)); setOnClickListener { showReadiness() }
            })
            val logCount=KeyguardSignalReceiver.guardianLog(this@MainActivity).size
            addView(TextView(this@MainActivity).apply {
                text="Diagnostics  •  $logCount retained events"
                textSize=11f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(dp(4),dp(7),dp(4),dp(7))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                fun quick(label:String, action:()->Unit)=TextView(this@MainActivity).apply {
                    text=label; textSize=13f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
                    background=Appearance.glass(this@MainActivity,22f,36,true); setPadding(dp(10),dp(11),dp(10),dp(11))
                    setOnClickListener { action() }
                }
                addView(quick("Logs") { showGuardianLogCenter() },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(4) })
                addView(quick("System Server") { showSystemServerDashboard() },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(4) })
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(2) })
        }
        AlertDialog.Builder(this).setView(body).setNegativeButton("Close",null).create().also { d -> d.setOnShowListener { d.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity, 34f, 84, true)); d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Appearance.accent(this@MainActivity)) }; d.show() }
    }

    /** Home presentation delegates all security actions to the original controls. */
    private fun applyHomeLayout(root: LinearLayout) {
        if (!PinStore(this).configured()) return
        fun label(text: String, size: Float = 14f) = TextView(this).apply {
            this.text = text; textSize = size
            setTextColor(Appearance.text(this@MainActivity))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Appearance.glass(this@MainActivity, 28f, 42, true)
        }
        fun action(text: String, click: () -> Unit) = label(text).apply {
            val icon = when {
                text.startsWith("Shizuku") -> R.drawable.ic_guardian_shizuku
                text.startsWith("Root") -> R.drawable.ic_guardian_root
                text.startsWith("Kiosk") -> R.drawable.ic_guardian_shield
                text.startsWith("Change PIN") -> R.drawable.ic_lock_security
                text.startsWith("Unlock") || text.startsWith("Fingerprint") -> R.drawable.ic_guardian_fingerprint
                else -> R.drawable.ic_system_health
            }
            val drawable = getDrawable(icon)?.mutate()
            drawable?.setTint(Appearance.accent(this@MainActivity)); drawable?.setBounds(0,0,dp(18),dp(18))
            setCompoundDrawables(drawable,null,null,null); compoundDrawablePadding=dp(6)
            setPadding(dp(12),dp(8),dp(12),dp(8))
            accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host:View, info:android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info); info.className="android.widget.Button"
                }
            }
            gravity = Gravity.CENTER; minimumHeight = dp(48)
            isFocusable = true; isClickable = true
            background = Appearance.glass(this@MainActivity, 22f, 34, true)
            setOnClickListener { click() }
        }
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val originals = descendants(root)
        fun control(text: String) = originals.filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
        fun delegated(text: String, original: String) = action(text) {
            control(original)?.performClick()
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "compact_details"
            visibility = View.GONE
        }
        // Preserve every original control, tag and handler for search and the dock.
        val old = (2 until root.childCount).map { root.getChildAt(it) }
        old.forEach {
            root.removeView(it)
            if (it !is android.widget.EditText || it.hint?.toString() != "Search settings") details.addView(it)
        }
        (root.getChildAt(0) as? TextView)?.apply { text = "D2 Guardian"; textSize = 28f }
        (root.getChildAt(1) as? TextView)?.apply {
            text = "${if (Prefs.enabled(this@MainActivity)) "Enabled" else "Disabled"}  ·  ${android.os.Build.MODEL}  ·  Android ${android.os.Build.VERSION.RELEASE}"
            textSize = 13f; setPadding(0, dp(4), 0, dp(12))
        }
        root.setPadding(dp(18), dp(48), dp(18), dp(20))
        val shortcuts=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf("Security" to "security", "SRP" to "tools", "Guardian" to "guardian", "Style" to "appearance").forEach { (title,page) ->
            shortcuts.addView(action(title) { pageNavigator?.invoke(page,null) },LinearLayout.LayoutParams(dp(112),-2).apply { marginEnd=dp(8) })
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(shortcuts) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val status = card()
        fun pair(parent: LinearLayout, first: View, second: View) {
            if (resources.configuration.fontScale >= 1.25f || resources.configuration.screenWidthDp < 360) {
                parent.addView(first, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                parent.addView(second, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                return
            }
            parent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val rootOk = RootManager.isAvailable()
        val shizukuOk = Prefs.shizukuEnabled(this) && runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val serverOk = KeyguardSignalReceiver.systemHealth(this).status == "CONNECTED"
        pair(status, action("Kiosk ${if (Prefs.kiosk(this)) "✓" else "○"}") { showReadiness() },
            action("Root ${if (rootOk) "✓" else "○"}") { showReadiness() })
        pair(status, action("Shizuku ${if (shizukuOk) "✓" else "○"}") { resolveShizuku() },
            action("System Server ${if (serverOk) "✓" else "○"}") { showSystemServerDashboard() })
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (Prefs.shizukuEnabled(this) && !shizukuOk) root.addView(action("Shizuku needs attention  ›") { resolveShizuku() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (Prefs.unlockMethod(this) == "fingerprint" && getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            root.addView(delegated("Fingerprint unavailable · Review method  ›", "Unlock method"),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val srpStatus = app.d2lock.root.DmcRecovery.status(this)
        if (Prefs.xposedMaster(this) && !srpStatus.targetsMet) root.addView(action("D2 SRP needs verification  ›") { showDmcStatus() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        root.addView(action("Readiness & guided tests  ›") { showReadiness() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val security = card()
        security.addView(label("Security", 20f))
        val method = Prefs.unlockMethod(this).replaceFirstChar { it.uppercase() }
        security.addView(label("$method login  ·  D2 PIN recovery ready", 13f))
        pair(security, delegated("Unlock method", "Unlock method"), delegated("Change PIN", "Change PIN"))
        root.addView(security, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        fun quickSwitch(title: String, original: String): View {
            val source = control(original) as? Switch
            return card().apply {
                addView(guardianSwitch().apply {
                    text = title; textSize = 14f; minimumHeight = dp(48)
                    setTextColor(Appearance.text(this@MainActivity)); isChecked = source?.isChecked == true
                    isEnabled = source != null && source.isEnabled
                    setOnCheckedChangeListener { _, checked ->
                        if (source != null && checked != source.isChecked) source.performClick()
                        refreshSettingsPreservingPosition()
                        Toast.makeText(this@MainActivity, "$title updated", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        val quick = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pair(quick, quickSwitch("Guardian", "Show D2 when the screen wakes"),
            quickSwitch("Kiosk", "Require D2 authentication to leave"))
        val island = originals.filterIsInstance<Switch>().firstOrNull { it.text.toString().startsWith("Galaxy Island  •") }
        val islandCard = card().apply {
            addView(guardianSwitch().apply {
                text = "Galaxy Island"; textSize = 14f; minimumHeight = dp(48)
                setTextColor(Appearance.text(this@MainActivity)); isChecked = island?.isChecked == true
                isEnabled = island != null && island.isEnabled
                setOnCheckedChangeListener { _, checked ->
                    if (island != null && checked != island.isChecked) island.performClick()
                    refreshSettingsPreservingPosition()
                    Toast.makeText(this@MainActivity, "Galaxy Island updated", Toast.LENGTH_SHORT).show()
                }
            })
        }
        pair(quick, islandCard, action("Test Guardian  ›") { showReadiness() })
        root.addView(quick)
        val bridge = card()
        val bridgeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (Prefs.compactBridge(this@MainActivity)) View.VISIBLE else View.GONE
            val fp = KeyguardSignalReceiver.fingerprintHealth(this@MainActivity)
            val ui = KeyguardSignalReceiver.bridgeHealth(this@MainActivity)
            addView(label("SystemUI telemetry: ${if (ui.status == "CONNECTED") "Observed" else "No event this boot"}", 13f))
            addView(label("Fingerprint: ${fp.event ?: "No event this boot"}", 13f))
            addView(action("Open diagnostics  ›") { showSystemServerDashboard() })
        }
        val bridgeToggle = action("System bridge · ${if (serverOk) "Observed" else "Waiting"}") {

            val open = bridgeBody.visibility != View.VISIBLE
            bridgeBody.visibility = if (open) View.VISIBLE else View.GONE
            Prefs.setCompactBridge(this, open)
            (bridge.getChildAt(0) as? TextView)?.apply {
                text="System bridge · ${if (serverOk) "Observed" else "Waiting"}  ${if(open) "⌃" else "⌄"}"
                contentDescription="System bridge, ${if(open) "expanded" else "collapsed"}"
            }
        }
        bridgeToggle.contentDescription="System bridge, ${if(bridgeBody.visibility == View.VISIBLE) "expanded" else "collapsed"}"
        bridgeToggle.text="System bridge · ${if (serverOk) "Observed" else "Waiting"}  ${if(bridgeBody.visibility == View.VISIBLE) "⌃" else "⌄"}"
        bridge.addView(bridgeToggle)
        bridge.addView(bridgeBody)
        root.addView(bridge, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        root.addView(label("Samsung may require its PIN after reboot. Keep your fingerprint enrolled.", 12f))
        root.addView(action("Explore tools & settings  ›") {
            pageNavigator?.invoke("tools", null)
            return@action
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        root.addView(details)
    }

    private fun revealCompactDetails(root: LinearLayout) {
        root.findViewWithTag<View>("compact_details")?.visibility = View.VISIBLE
    }

    private fun sectionPage(title: String) = SettingsNavigation.pageForSection(title)

    private fun installPageNavigation(root: LinearLayout, scroll: ScrollView, selected: (String)->Unit) {
        val details=root.findViewWithTag<LinearLayout>("compact_details")
        if(details==null) { pageNavigator=null; return }
        // Split nested appearance/media sections into their own focused groups.
        val original=(0 until details.childCount).map { details.getChildAt(it) }
        val flattened=mutableListOf<View>()
        original.forEach { view ->
            if(view is LinearLayout && (0 until view.childCount).any { sectionTitles.containsKey(view.getChildAt(it)) }) {
                val children=(0 until view.childCount).map { view.getChildAt(it) }
                children.forEach { view.removeView(it); flattened.add(it) }
            } else flattened.add(view)
        }
        details.removeAllViews()
        val groups=linkedMapOf<String,LinearLayout>()
        var section="DEVICE & OVERVIEW"
        flattened.forEach { view ->
            sectionTitles[view]?.let { section=it }
            val container=groups.getOrPut(section) { LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; tag="detail_group:$section" } }
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view)
        }
        groups.entries.removeAll { (_, content) -> content.childCount==1 && sectionTitles.containsKey(content.getChildAt(0)) }
        val homeViews=(2 until root.childCount).map { root.getChildAt(it) }.filter { it!==details }
        val summaries=linkedMapOf<String,View>()
        groups.forEach { (title,content) ->
            val row=TextView(this).apply {
                text=title.lowercase().replaceFirstChar { it.uppercase() }+"  ›\n"+when(title) {
                    "SECURITY CENTER" -> "Unlock method, PIN recovery, and passkeys"
                    "FINGERPRINT CENTER" -> "Sensor setup, availability, and testing"
                    "D2 SRP" -> "Samsung Recovery Protection status and checks"
                    "SYSTEM INTEGRATION" -> "Root, Shizuku, and system connections"
                    "LOCK & WAKE" -> "Screen wake and lock behavior"
                    "GUARDIAN & KIOSK" -> "Kiosk authentication and shade protection"
                    "CLOCK & WEATHER" -> "Clock style, size, and weather"
                    "MEDIA" -> "Player appearance and controls"
                    "ABOUT & DATA" -> "Guides, credits, updates, and backups"
                    else -> "Open controls and setup guidance"
                }
                textSize=16f; minimumHeight=dp(64); setPadding(dp(18),dp(16),dp(18),dp(16))
                setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,28f,38,true)
                isFocusable=true; contentDescription="Open $title settings"
                setOnClickListener { pageNavigator?.invoke(SettingsNavigation.pageForSection(title)+"|"+title,null) }
            }
            summaries[title]=row
            details.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            details.addView(content,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
        val back=TextView(this).apply {
            text="‹ Back"; textSize=16f; minimumHeight=dp(48); setTextColor(Appearance.text(this@MainActivity))
            setOnClickListener { handleSettingsBack() }
        }
        root.addView(back,2,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        fun routeFor(target:View):String {
            summaries.entries.firstOrNull { it.value===target }?.key?.let { return SettingsNavigation.pageForSection(it)+"|"+it }
            var child=target
            while(child.parent is View && child.parent!==details) child=child.parent as View
            val title=groups.entries.firstOrNull { it.value===child }?.key
            return if(title==null) "home" else SettingsNavigation.pageForSection(title)+"|"+title
        }
        fun render(route:String,target:View?) {
            val actual=if(target!=null) routeFor(target) else route
            val page=actual.substringBefore('|'); val detail=actual.substringAfter('|',"")
            dockDestination=actual
            homeViews.forEach { it.visibility=if(page=="home") View.VISIBLE else View.GONE }
            details.visibility=if(page=="home") View.GONE else View.VISIBLE
            groups.forEach { (title,content) ->
                summaries[title]?.visibility=if(detail.isEmpty() && SettingsNavigation.pageForSection(title)==page) View.VISIBLE else View.GONE
                content.visibility=if(title==detail) View.VISIBLE else View.GONE
            }
            back.visibility=if(detail.isNotEmpty()) View.VISIBLE else View.GONE
            (root.getChildAt(0) as? TextView)?.text=if(detail.isNotEmpty()) detail.lowercase().replaceFirstChar { it.uppercase() } else when(page) { "home"->"D2 Guardian"; "appearance"->"Appearance"; else->page.replaceFirstChar { it.uppercase() } }
            selected(page)
            scroll.post {
                if(target==null) scroll.scrollTo(0,pageScrollPositions[actual] ?: 0)
                else {
                    val summary=summaries.entries.firstOrNull { it.value===target }?.key
                    val visibleTarget=summary?.let { groups[it]?.getChildAt(0) } ?: target
                    val rect=android.graphics.Rect(); visibleTarget.getDrawingRect(rect)
                    root.offsetDescendantRectToMyCoords(visibleTarget,rect)
                    scroll.smoothScrollTo(0,(rect.top-dp(18)).coerceAtLeast(0)); flashSettingsTarget(visibleTarget)
                }
            }
        }
        pageNavigator={ destination,target ->
            pageScrollPositions[dockDestination]=scroll.scrollY
            val next=if(target!=null) routeFor(target) else destination
            if(!navigatingBack && next!=dockDestination) navigationHistory.addLast(dockDestination)
            render(next,target)
        }
        val restored=dockDestination.takeIf { it.substringBefore('|') in listOf("home","security","guardian","tools","appearance") && (it.substringAfter('|',"").isEmpty() || groups.containsKey(it.substringAfter('|'))) } ?: "home"
        render(restored,null)
    }

    private fun handleSettingsBack() {
        if (authorized && pageNavigator != null && dockDestination != "home") {
            val previous=if(navigationHistory.isEmpty()) dockDestination.substringBefore('|').takeIf { dockDestination.contains('|') } ?: "home" else navigationHistory.removeLast()
            navigatingBack=true
            try { pageNavigator?.invoke(previous,null) } finally { navigatingBack=false }
        } else finish()
    }

    override fun onSaveInstanceState(outState:Bundle) {
        val scroll=findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<ScrollView>("settings_scroll")
        if(authorized && scroll!=null) pageScrollPositions[dockDestination]=scroll.scrollY
        outState.putString("settings_route",dockDestination)
        outState.putBundle("settings_positions",Bundle().apply { pageScrollPositions.forEach { (key,value) -> putInt(key,value) } })
        outState.putStringArrayList("settings_history",ArrayList(navigationHistory))
        super.onSaveInstanceState(outState)
    }

    // API 31–32 dispatch Back as a key event; API 33+ uses the gesture callback.
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33 && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            handleSettingsBack()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showGlobalSearch(root:LinearLayout, scroll:ScrollView) {
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(16)) }
        val input=android.widget.EditText(this).apply { hint="Search D2 settings"; textSize=16f; setSingleLine(true); setTextColor(Appearance.text(this@MainActivity)); setHintTextColor(Appearance.secondary(this@MainActivity)) }
        body.addView(input,LinearLayout.LayoutParams(-1,dp(56)))
        val health=TextView(this).apply { text="Guardian health & readiness  ›"; minimumHeight=dp(48); setTextColor(Appearance.text(this@MainActivity)) }
        body.addView(health)
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(results) },LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.45f).toInt()))
        val dialog=AlertDialog.Builder(this).setTitle("CORE · Search").setView(body).setNegativeButton("Close",null).create()
        health.setOnClickListener { dialog.dismiss(); showReadiness() }
        dialog.setOnDismissListener { input.clearFocus() }
        val entries=mutableListOf<Triple<TextView,View,String>>()
        fun walk(view:View,section:String) {
            if(view is ViewGroup) {
                if(view.isClickable) {
                    val label=(0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull { it.text.isNotBlank() }
                    if(label!=null) entries.add(Triple(label,view,section))
                }
                var current=section
                for(i in 0 until view.childCount) { val child=view.getChildAt(i); sectionTitles[child]?.let { current=it }; walk(child,current) }
            } else if(view is TextView && view.text.isNotBlank() && (view.isClickable || view.tag?.toString()?.endsWith("_label")==true)) {
                entries.add(Triple(view,view,section))
            }
        }
        walk(root,"Home")
        fun update(query:String) {
            results.removeAllViews()
            val matches=entries.filter { query.isNotBlank() && (it.first.text.toString()+" "+it.third).contains(query,true) }.distinctBy { it.first.text.toString()+it.third }.take(40)
            if(matches.isEmpty()) results.addView(TextView(this).apply { text=if(query.isBlank()) "Search controls, features, or section names" else "No matching controls"; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(12),0,dp(12)) })
            matches.forEach { (label,target,section) -> results.addView(TextView(this).apply {
                text=label.text.toString()+if(label is Switch) " · ${if(label.isChecked) "On" else "Off"}" else ""; append("\n$section")
                textSize=14f; minimumHeight=dp(56); setPadding(dp(12),dp(10),dp(12),dp(10)); setTextColor(Appearance.text(this@MainActivity)); background=Appearance.glass(this@MainActivity,20f,28,true)
                setOnClickListener {
                    dialog.dismiss()
                    if(pageNavigator!=null) pageNavigator?.invoke("tools",target)
                    else { revealCompactDetails(root); scroll.post { val r=android.graphics.Rect(); target.getDrawingRect(r); root.offsetDescendantRectToMyCoords(target,r); scroll.smoothScrollTo(0,r.top); flashSettingsTarget(target) } }
                    if(!target.isEnabled) GuardianDialogs.showInfo(this@MainActivity,"Control unavailable","This control is disabled by its current setup or development state. Review the guidance in its settings section.")
                }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) }) }
        }
        input.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { update(s?.toString().orEmpty()) }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        update("")
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(Appearance.glass(this,32f,82,true)); input.requestFocus() }
        dialog.show()
    }

    private fun settingsHost(root: LinearLayout): ViewGroup {
        applyHomeLayout(root)
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
        val scroll = ScrollView(this).apply {
            tag = "settings_scroll"
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            // The Guardian dock intentionally floats above the settings surface.
            // Reserve scrollable clearance so the final control can move fully above it.
            setPadding(0, 0, 0, dp(118))
            addView(root)
        }
        host.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        scroll.post { scroll.scrollTo(0, settingsScrollY) }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = Appearance.glass(this@MainActivity, 36f, 82, true)
            elevation = dp(24).toFloat()
        }
        val destinations = listOf(
            Triple(R.drawable.ic_nav_home, "Home", "home"),
            Triple(R.drawable.ic_lock_security, "Security", "security"),
            Triple(R.drawable.ic_guardian_shield, "Guardian", "guardian"),
            Triple(R.drawable.ic_nav_settings, "Tools", "tools"),
            Triple(R.drawable.ic_palette, "Style", "appearance")
        )
        // Beta.2 Guardian Dock: the center core is a live health surface, not a fake
        // security indicator. Green requires the configured protection stack; amber
        // means Guardian is usable but one or more optional integrations are absent.
        val rootAvailable = RootManager.isAvailable()
        val shizukuAvailable = shizukuAuthorized()
        val islandPaired = app.d2lock.bridge.IslandBridge.enabled(this)
        val islandSync = !islandPaired || Prefs.xposedIslandGuardianSync(this)
        val guardianHealthy = Prefs.enabled(this) && PinStore(this).configured() &&
            (!Prefs.rootMode(this) || rootAvailable) &&
            (!Prefs.shizukuEnabled(this) || shizukuAvailable) &&
            (!Prefs.kiosk(this) || rootAvailable) &&
            islandSync
        val core = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = if (guardianHealthy) "Guardian Core protected. Tap for system health." else "Guardian Core needs attention. Tap for system health."
            background = Appearance.glass(this@MainActivity, 32f, 72, true)
            elevation = dp(30).toFloat()
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_core_search)
                imageTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(this@MainActivity).apply {
                text = if (guardianHealthy) "CORE" else "CHECK"
                textSize = 8f
                letterSpacing = .08f
                gravity = Gravity.CENTER
                setTextColor(Appearance.text(this@MainActivity))
            }, LinearLayout.LayoutParams(-1, dp(15)))
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showGuardianLogCenter()
                true
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                animate().cancel()
                scaleX = .90f; scaleY = .90f
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                showGlobalSearch(root, scroll)
            }
        }
        val items = mutableListOf<LinearLayout>()
        fun select(item: LinearLayout) {
            items.forEach { candidate ->
                candidate.animate().cancel()
                candidate.background = null
                candidate.scaleX = 1f
                candidate.scaleY = 1f
                candidate.setPadding(dp(2), dp(2), dp(2), dp(2))
                (candidate.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Appearance.secondary(this@MainActivity))
                (candidate.getChildAt(1) as? TextView)?.apply {
                    textSize = 9f
                    setTextColor(Appearance.secondary(this@MainActivity))
                }
            }
            item.background = Appearance.glass(this@MainActivity, 28f, 48, true)
            item.setPadding(dp(7), dp(2), dp(7), dp(2))
            (item.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Appearance.accent(this@MainActivity))
            (item.getChildAt(1) as? TextView)?.apply {
                textSize = 11f
                setTextColor(Appearance.text(this@MainActivity))
            }
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
                if (index == 0) setOnLongClickListener { showGuardianCoreHealth(); true }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (index == 0) Appearance.accent(this@MainActivity) else Appearance.secondary(this@MainActivity)
                    )
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    contentDescription = null
                }, LinearLayout.LayoutParams(-1, dp(29)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Appearance.secondary(this@MainActivity))
                }, LinearLayout.LayoutParams(-1, dp(18)))
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    pageNavigator?.invoke(target, null)
                    select(this)
                }
            }
            items += item
            nav.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (index > 0) marginStart = dp(2)
            })
        }
        items.getOrNull(destinations.indexOfFirst { it.third == dockDestination }.coerceAtLeast(0))?.let { select(it) }
        host.addView(nav, android.widget.FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), 0, dp(90), dp(18))
        })
        host.addView(core, android.widget.FrameLayout.LayoutParams(dp(68), dp(72), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(12), dp(18))
        })
        core.contentDescription = "CORE search. Tap to search all settings; long press for health and logs."
        core.setOnLongClickListener { showGuardianCoreHealth(); true }
        installPageNavigation(root, scroll) { page ->
            destinations.indexOfFirst { it.third == page }.takeIf { it >= 0 }?.let { select(items[it]) }
        }

        // Keep the Guardian Dock out of the way while reading settings.
        // Downward scrolling hides both the dock and its CORE/CHECK button; upward
        // scrolling restores them. Returning to the top always restores the dock.
        var dockVisible = true
        var dockExitedForScroll = false
        val scrollThreshold = dp(6)
        val dockScrollBehavior = Prefs.floatingBarScrollBehavior(this)
        fun setDockVisible(visible: Boolean) {
            if (dockVisible == visible) return
            dockVisible = visible
            val navDistance = (nav.height.takeIf { it > 0 } ?: dp(72)) + dp(34)
            val coreDistance = navDistance
            nav.animate().cancel()
            core.animate().cancel()
            if (visible) {
                nav.visibility = View.VISIBLE
                core.visibility = View.VISIBLE
                nav.animate().translationY(0f).alpha(1f).setDuration(180).start()
                core.animate().translationY(0f).alpha(1f).setDuration(180).start()
            } else {
                nav.animate().translationY(navDistance.toFloat()).alpha(0f).setDuration(160)
                    .withEndAction { if (!dockVisible) nav.visibility = View.INVISIBLE }.start()
                core.animate().translationY(coreDistance.toFloat()).alpha(0f).setDuration(160)
                    .withEndAction { if (!dockVisible) core.visibility = View.INVISIBLE }.start()
            }
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val delta = scrollY - oldScrollY
            when (dockScrollBehavior) {
                "always_visible" -> setDockVisible(true)
                "exit_after_scroll" -> {
                    if (!dockExitedForScroll && scrollY > dp(4) && kotlin.math.abs(delta) > scrollThreshold) {
                        dockExitedForScroll = true
                        setDockVisible(false)
                    }
                }
                else -> when {
                    scrollY <= dp(4) -> setDockVisible(true)
                    delta > scrollThreshold -> setDockVisible(false)
                    delta < -scrollThreshold -> setDockVisible(true)
                }
            }
        }

        host.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            (nav.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = dp(18) + bars.bottom
                nav.layoutParams = params
            }
            (core.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = dp(18) + bars.bottom
                core.layoutParams = params
            }
            scroll.setPadding(0, 0, 0, dp(118) + bars.bottom)
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
        Appearance.apply(this)
        refreshSettingsPreservingPosition()
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
            setOnTouchListener { v, event ->
                when(event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(.985f).scaleY(.985f).alpha(.88f).setDuration(70).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(130).start()
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                action()
            }
        }, rowParams())
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 12f; letterSpacing = .12f
            setTextColor(Appearance.secondary(this@MainActivity))
            setPadding(dp(8), dp(14), 0, dp(7))
        })
        sectionTitles[parent.getChildAt(parent.childCount - 1)] = title
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
                    !enabled -> "The Shizuku service may keep running, but Kiosk D2 Boot Guardian will not use it."
                    granted -> "Service running · Authorized"
                    running -> "Tap below to authorize Kiosk D2 Boot Guardian."
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

    private fun showGuardianLogCenter(filter: String = "ALL") {
        val all = KeyguardSignalReceiver.guardianLog(this)
        val categories = listOf("ALL","SYSTEM_SERVER","BIOMETRIC","KEYGUARD","BOOT","GUARDIAN")
        val panel = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16))
            addView(TextView(this@MainActivity).apply {
                text="GUARDIAN LOG CENTER"; textSize=11f; letterSpacing=.10f; setTextColor(Appearance.secondary(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text="${all.size} retained events  •  ${if(filter=="ALL") "All categories" else filter.replace("_"," ")}"
                textSize=20f; setTextColor(Appearance.text(this@MainActivity)); setPadding(0,dp(4),0,dp(10))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                categories.forEach { cat ->
                    addView(TextView(this@MainActivity).apply {
                        text=cat.replace("_"," "); textSize=9f; gravity=Gravity.CENTER
                        setTextColor(if(cat==filter) Appearance.accent(this@MainActivity) else Appearance.secondary(this@MainActivity))
                        background=Appearance.glass(this@MainActivity,18f,if(cat==filter) 52 else 24,true)
                        setPadding(dp(5),dp(7),dp(5),dp(7)); setOnClickListener { showGuardianLogCenter(cat) }
                    },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(2) })
                }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            val shown=all.mapNotNull { line ->
                val p=line.split("|",limit=4); if(p.size<4) null else p
            }.filter { filter=="ALL" || it[2]==filter }
            addView(ScrollView(this@MainActivity).apply {
                addView(TextView(this@MainActivity).apply {
                    text=if(shown.isEmpty()) "No events in this category yet."
                    else shown.joinToString("\n") { p ->
                        val t=runCatching { java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US).format(java.util.Date(p[0].toLong())) }.getOrDefault("--:--:--.---")
                        "$t  ${p[1]}  [${p[2]}]  ${p[3]}"
                    }
                    textSize=10f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                    setPadding(dp(10),dp(10),dp(10),dp(10))
                })
                background=Appearance.glass(this@MainActivity,20f,24,true)
            },LinearLayout.LayoutParams(-1,dp(390)))
            addView(TextView(this@MainActivity).apply {
                text="Sensitive credential and fingerprint payloads are intentionally excluded."
                textSize=10f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(8),0,0)
            })
        }
        AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("Close",null)
            .setNeutralButton("Clear") { _,_ -> KeyguardSignalReceiver.clearGuardianLog(this) }
            .setPositiveButton("Copy") { _,_ ->
                val text=all.joinToString("\n")
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Guardian Log Center",text))
                Toast.makeText(this,"Guardian log copied",Toast.LENGTH_SHORT).show()
            }.create().also { d ->
                d.setOnShowListener { d.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,32f,82,true)) }; d.show()
            }
    }

    private fun showSystemServerDashboard() {
        val sys = KeyguardSignalReceiver.systemHealth(this)
        val ui = KeyguardSignalReceiver.bridgeHealth(this)
        val fp = KeyguardSignalReceiver.fingerprintHealth(this)
        val metrics = KeyguardSignalReceiver.systemMetrics(this)
        val pipeline = KeyguardSignalReceiver.pipelineMetrics(this)
        val decisions = KeyguardSignalReceiver.decisionMetrics(this)
        fun age(ms: Long?): String = when {
            ms == null -> "Waiting"
            ms < 1_000 -> "Now"
            ms < 60_000 -> "${ms / 1_000}s ago"
            else -> "${ms / 60_000}m ago"
        }
        fun metric(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = Appearance.glass(this@MainActivity, 20f, 34, true)
            addView(TextView(this@MainActivity).apply {
                text=value; textSize=18f; gravity=Gravity.CENTER; setTextColor(Appearance.text(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text=label; textSize=10f; gravity=Gravity.CENTER; setTextColor(Appearance.secondary(this@MainActivity))
            })
        }
        val panel = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(8),dp(14),dp(26))
            addView(TextView(this@MainActivity).apply {
                text="SYSTEM SERVER COMMAND CENTER"; textSize=11f; letterSpacing=.10f
                setTextColor(Appearance.secondary(this@MainActivity))
            })
            addView(TextView(this@MainActivity).apply {
                text=if(sys.event != null) "Framework bridge active" else "Framework bridge · waiting"
                textSize=22f; setTextColor(Appearance.text(this@MainActivity)); setPadding(0,dp(4),0,dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text="System Server ${if(sys.event != null) "OBSERVED" else "WAITING"}  •  Last activity ${age(sys.ageMs)}  •  System UI ${ui.status}  •  LSPosed ${if(Prefs.xposedMaster(this@MainActivity)) "ON" else "OFF"}"
                textSize=12f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(12))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(metric("EVENTS",metrics.total.toString()),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(3) })
                addView(metric("WAKE",metrics.wakeSleep.toString()),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3); rightMargin=dp(3) })
                addView(metric("KEYGUARD",metrics.keyguard.toString()),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3) })
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(metric("BIOMETRIC",metrics.biometric.toString()),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(3) })
                addView(metric("LOCK SETTINGS",metrics.lockSettings.toString()),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3); rightMargin=dp(3) })
                addView(metric("LAST",age(sys.ageMs)),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(3) })
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(12),dp(10),dp(12),dp(10))
                background=Appearance.glass(this@MainActivity,22f,24,true)
                addView(TextView(this@MainActivity).apply {
                    text="LIVE STATE"; textSize=11f; letterSpacing=.08f; setTextColor(Appearance.secondary(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text="Framework  •  ${sys.event ?: "Waiting"}\nMethod  •  ${sys.method ?: "—"}\nSystemUI heartbeat  •  ${age(ui.ageMs)}\nFingerprint  •  ${fp.event ?: "Waiting"}"
                    textSize=13f; setLineSpacing(0f,1.15f); setTextColor(Appearance.text(this@MainActivity)); setPadding(0,dp(6),0,0)
                })
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="BIOMETRIC + BOOT PIPELINE"; textSize=11f; letterSpacing=.08f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                val ordered = pipeline.timeline.mapNotNull { line ->
                    val p=line.split("|",limit=3); if(p.size<3) null else Triple(p[0].toLongOrNull() ?: 0L,p[1],p[2])
                }.sortedBy { it.first }
                text=if(ordered.isEmpty()) "Waiting for pipeline telemetry. Soft reboot or authenticate once to populate this view."
                else {
                    val base=ordered.first().first
                    val recent=ordered.takeLast(8)
                    val prefix=if(ordered.size>recent.size) "${ordered.size-recent.size} earlier events hidden\n" else ""
                    prefix + recent.joinToString("\n") { item ->
                        val delta=(item.first-base).coerceAtLeast(0L)
                        "+${delta}ms  •  ${item.second}  •  ${item.third}"
                    }
                }
                textSize=11f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12),dp(10),dp(12),dp(10)); background=Appearance.glass(this@MainActivity,20f,26,true)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="Pipeline tracks event timing only. Android remains the authority for fingerprint verification."
                textSize=10f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text="HOOK COVERAGE + COMPATIBILITY"; textSize=11f; letterSpacing=.08f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                val active=metrics.timeline.mapNotNull { it.split("|",limit=3).getOrNull(2) }.toSet()
                fun seen(name:String)=if(active.any { it.contains(name) }) "OBSERVED" else "NO EVENT YET"
                text="LockSettingsService  •  ${seen("LockSettings")}\nBiometricService  •  ${seen("BiometricService")}\nKeyguardController  •  ${seen("KeyguardController")}\nActivityTaskManager  •  ${seen("ActivityTaskManager")}\nPowerManagerService  •  ${seen("PowerManagerService")}\n\nFirmware  •  ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}\nBuild  •  ${android.os.Build.DISPLAY}"
                textSize=11f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12),dp(10),dp(12),dp(10)); background=Appearance.glass(this@MainActivity,20f,24,true)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="GUARDIAN DECISIONS  •  ${decisions.suppressed} suppressed/deferred"; textSize=11f; letterSpacing=.06f
                setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text=if(decisions.timeline.isEmpty()) "No Guardian decisions captured yet." else decisions.timeline.take(8).joinToString("\n") { line ->
                    val p=line.split("|",limit=3); if(p.size<3) line else "${p[1]}  •  ${p[2]}"
                }
                textSize=10f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12),dp(10),dp(12),dp(10)); background=Appearance.glass(this@MainActivity,20f,22,true)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                val events=pipeline.timeline.mapNotNull { l -> val p=l.split("|",limit=3); p.getOrNull(0)?.toLongOrNull()?.let { Triple(it,p.getOrElse(1){""},p.getOrElse(2){""}) } }.sortedBy { it.first }
                val wake=events.lastOrNull { it.second=="WAKE" }
                val request=events.lastOrNull { it.second=="D2_LAUNCH_REQUEST" || it.second=="D2_LAUNCH_REQUESTED" }
                val accepted=events.lastOrNull { it.second=="D2_LAUNCH_ACCEPTED" }
                val visible=events.lastOrNull { it.second=="D2_VISIBLE" }
                val firstDraw=events.lastOrNull { it.second=="D2_FIRST_DRAW" }
                val healthy=events.lastOrNull { it.second=="D2_HEALTHY" }
                fun delta(a: Triple<Long,String,String>?, b: Triple<Long,String,String>?) =
                    if(a!=null&&b!=null&&b.first>=a.first) "${b.first-a.first} ms" else "Waiting"
                val path=accepted?.third ?: request?.third ?: "Waiting"
                text="WAKE / VISIBILITY MONITOR\nWake → request  •  ${delta(wake,request)}\nRequest → visible  •  ${delta(request,visible)}\nVisible → first draw  •  ${delta(visible,firstDraw)}\nFirst draw → healthy  •  ${delta(firstDraw,healthy)}\nLaunch path  •  $path"
                textSize=11f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12),dp(10),dp(12),dp(10)); background=Appearance.glass(this@MainActivity,20f,24,true)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="SAFETY MODE  •  OBSERVE / COORDINATE ONLY\nCredential verification, Gatekeeper data and biometric results are never modified."
                textSize=10f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(dp(12),dp(9),dp(12),dp(9))
                background=Appearance.glass(this@MainActivity,20f,22,true)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            addView(TextView(this@MainActivity).apply {
                text="RECENT FRAMEWORK EVENTS"; textSize=11f; letterSpacing=.08f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,0,0,dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text=if(metrics.timeline.isEmpty()) "No system_server events captured yet. Soft reboot after enabling the System Framework scope."
                else metrics.timeline.joinToString("\n") { line ->
                    val p=line.split("|",limit=3)
                    if(p.size<3) line else {
                        val t=runCatching { java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date(p[0].toLong())) }.getOrDefault("--:--:--")
                        "$t  •  ${p[1]}  •  ${p[2]}"
                    }
                }
                textSize=11f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(Appearance.text(this@MainActivity))
                setPadding(dp(12),dp(10),dp(12),dp(10)); background=Appearance.glass(this@MainActivity,20f,22,true)
            })
            addView(TextView(this@MainActivity).apply {
                text="Guardian decisions remain fail-safe: framework telemetry coordinates D2, but credential and biometric verification stay with Android."
                textSize=11f; setTextColor(Appearance.secondary(this@MainActivity)); setPadding(0,dp(10),0,0)
            })
        }
        val dashboardScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(10))
            addView(panel, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setView(dashboardScroll).setNegativeButton("Close",null)
            .setPositiveButton("Refresh") { _,_ -> showSystemServerDashboard() }
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(Appearance.glass(this@MainActivity,32f,98,true))
                    dialog.window?.setDimAmount(0.82f)
                    dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val maxHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                    dashboardScroll.layoutParams = dashboardScroll.layoutParams?.apply { height = maxHeight }
                        ?: android.view.ViewGroup.LayoutParams(-1, maxHeight)
                }
                dialog.show()
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
    private fun refreshSettingsPreservingPosition() {
        if (isFinishing || isDestroyed || !authorized || appStopped) return
        settingsScrollY = findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<ScrollView>("settings_scroll")?.scrollY ?: settingsScrollY
        pageScrollPositions[dockDestination]=settingsScrollY
        setContentView(buildSettings())
    }
    private fun refreshShizukuUi() = runOnUiThread { refreshSettingsPreservingPosition() }
    private fun addChoice(parent: LinearLayout, title: String, choices: List<String>, selected: Int, save: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Appearance.glass(this@MainActivity, 22f, 28, true)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Appearance.text(this@MainActivity))
        }
        val valueView = TextView(this).apply {
            text = choices[selected.coerceIn(0, choices.lastIndex)] + "   ›"
            textSize = 12f
            setTextColor(Appearance.accent(this@MainActivity))
            setPadding(0, dp(2), 0, 0)
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
                    background = Appearance.glass(this@MainActivity, 30f, if (index == current) 58 else 32, true)
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
        parent.addView(card, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(5) })
    }
    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(5) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requestRuntimePermissions() {
        val missing = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.CAMERA
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.READ_PHONE_STATE
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
