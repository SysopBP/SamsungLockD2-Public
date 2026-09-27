package app.d2lock.lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import app.d2lock.Appearance
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.AlarmManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.util.Log
import android.os.Vibrator
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.d2lock.Prefs
import app.d2lock.MainActivity
import app.d2lock.root.RootKiosk
import app.d2lock.root.KioskD2Guardian
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi
import app.d2lock.security.PatternStore
import app.d2lock.security.PatternUi
import app.d2lock.widget.DoubleTap
import app.d2lock.media.MediaControllerBridge
import app.d2lock.livehub.LiveHubCard
import app.d2lock.livehub.LiveHubKind
import app.d2lock.livehub.LiveHubStore
import app.d2lock.notifications.NotificationStore
import app.d2lock.notifications.LockNotificationListener
import app.d2lock.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LockScreenActivity : Activity() {
    companion object {
        private val fingerprintSessionLock = Any()
        @Volatile private var fingerprintSessionActive = false
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var battery: TextView
    private lateinit var weather: TextView
    private lateinit var notifications: LinearLayout
    private lateinit var liveBanner: TextView
    private var bannerKey: String? = null
    private val hideBanner = Runnable {
        if (::liveBanner.isInitialized) liveBanner.visibility = View.GONE
        bannerKey = null
    }
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var playPause: TextView
    private lateinit var mediaArtwork: ImageView
    private lateinit var mediaProgress: ProgressBar
    private lateinit var mediaTime: TextView
    private lateinit var mediaPanel: LinearLayout
    private lateinit var mediaHeader: LinearLayout
    private lateinit var mediaControls: LinearLayout
    private lateinit var media: MediaControllerBridge
    private var torchOn = false
    private var preview = false
    private var unlocking = false
    private var healthyReported = false
    private lateinit var kioskStatus: TextView
    private var chargingOverlay: LinearLayout? = null
    private var chargingOverlayHide: Runnable? = null
    private var lastChargingState = false
    private var pinDialog: AlertDialog? = null
    private var biometricCancel: CancellationSignal? = null
    private var biometricRunning = false
    private var ownsBiometricSession = false
    private var fingerprintRestartPending = false
    private var fingerprintGlass: LinearLayout? = null
    private var fingerprintGlyph: TextView? = null
    private var fingerprintLoading: ProgressBar? = null
    private var fingerprintStatus: TextView? = null
    private var fingerprintPulse: ObjectAnimator? = null
    private var fingerprintDismissed = false
    private var wallpaperActive = false
    private var emptyTapAt = 0L
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private val wallpaperAnimations = mutableListOf<ObjectAnimator>()

    private val ticker = object : Runnable {
        override fun run() {
            val time = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            val requestedLayout = Prefs.clockLayout(this@LockScreenActivity)
            val stacked = requestedLayout == "stacked" ||
                (requestedLayout == "auto" && Prefs.clockAdaptive(this@LockScreenActivity) &&
                    ((::media.isInitialized && media.isPlaying()) || NotificationStore.items.size >= 2))
            clock.text = if (stacked) time.replace(':', '\n') else time
            applyAdaptiveClockPosition(stacked)
            date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            val mediaName = media.title()
            val hasMedia = mediaName.isNotBlank() || media.artist().isNotBlank() || media.durationMs() > 0L || media.isPlaying()
            mediaTitle.text = mediaName.ifBlank { "No media playing" }
            mediaArtist.text = media.artist().ifBlank { if (hasMedia) "Media session" else "Tap play in an app to show controls" }
            if (::mediaPanel.isInitialized) {
                mediaPanel.layoutParams?.let { lp ->
                    val target = dp(if (hasMedia) 154 else 70)
                    if (lp.height != target) { lp.height = target; mediaPanel.layoutParams = lp }
                }
                mediaProgress.visibility = if (hasMedia) View.VISIBLE else View.GONE
                mediaControls.visibility = if (hasMedia) View.VISIBLE else View.GONE
                mediaTime.visibility = if (hasMedia) View.VISIBLE else View.GONE
            }
            playPause.text = if (media.isPlaying()) "Ⅱ" else "▶"
            val artwork = media.artwork()
            if (artwork != null) {
                mediaArtwork.setImageBitmap(artwork)
                mediaArtwork.imageTintList = null
                mediaArtwork.setPadding(0, 0, 0, 0)
            } else {
                mediaArtwork.setImageResource(app.d2lock.R.drawable.ic_media_music)
                mediaArtwork.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                mediaArtwork.setPadding(dp(13), dp(13), dp(13), dp(13))
            }
            val duration = media.durationMs()
            val position = media.positionMs().coerceAtMost(if (duration > 0L) duration else Long.MAX_VALUE)
            mediaProgress.max = 1000
            mediaProgress.progress = if (duration > 0L) ((position * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
            fun mediaClock(ms: Long): String {
                val total = (ms / 1000L).coerceAtLeast(0L)
                return "%d:%02d".format(total / 60L, total % 60L)
            }
            mediaTime.text = if (duration > 0L) "${mediaClock(position)}  •  ${mediaClock(duration)}" else media.album().ifBlank { "Media session" }
            if (mediaName.isNotBlank()) {
                LiveHubStore.publish(LiveHubCard("media", LiveHubKind.MEDIA, mediaName, media.artist()))
            } else LiveHubStore.remove("media")
            handler.postDelayed(this, 1000)
        }
    }

    private val quickSettingsGuard = object : Runnable {
        override fun run() {
            if (!preview && Prefs.quickSettingsGuard(this@LockScreenActivity) && hasWindowFocus()) {
                @Suppress("MissingPermission")
                runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }
            }
            handler.postDelayed(this, 350)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
            val percent = if (Prefs.showBatteryPercent(this@LockScreenActivity)) "  $level%" else ""
            battery.text = if (charging) "  $percent · Charging" else "  $percent"
            battery.setCompoundDrawablesWithIntrinsicBounds(app.d2lock.R.drawable.ic_lock_battery, 0, 0, 0)
            battery.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Appearance.text(this@LockScreenActivity, true))
            battery.compoundDrawablePadding = dp(4)
            battery.background = Appearance.glass(this@LockScreenActivity, 18f, 26, false)
            battery.setPadding(dp(9), dp(4), dp(9), dp(4))
            if (charging && level >= 0) {
                LiveHubStore.publish(LiveHubCard("charging", LiveHubKind.CHARGING, "Charging", "$level%", level / 100f))
                if (!lastChargingState) showChargingOverlay(level)
            } else LiveHubStore.remove("charging")
            lastChargingState = charging
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Appearance.dark(this, true)) app.d2lock.R.style.Theme_D2_Dark else app.d2lock.R.style.Theme_SamsungLock)
        super.onCreate(savedInstanceState)
        PinUi.protect(this)
        preview = intent.getBooleanExtra("preview", false)
        if (!preview && !PinStore(this).configured()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (!preview) app.d2lock.bridge.IslandBridge.setLocked(this, true)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        media = MediaControllerBridge(this)
        val guardianUi = buildUi()
        setContentView(guardianUi)
        if (Prefs.experimentalLockMotion(this)) {
            guardianUi.alpha = 0f
            guardianUi.scaleX = .985f
            guardianUi.scaleY = .985f
            guardianUi.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (preview) finish() else authenticate()
            }
        }
        window.insetsController?.apply {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            setSystemBarsAppearance(if (Appearance.dark(this@LockScreenActivity, true)) 0 else lightBars, lightBars)
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        NotificationStore.onChanged = { runOnUiThread(::renderNotifications) }
        app.d2lock.notifications.CallNotificationStore.onChanged = { runOnUiThread(::renderNotifications) }
        NotificationStore.onPosted = { item -> runOnUiThread { showLiveNotification(item) } }
        renderNotifications()
        WeatherRepository.load(this) { value -> runOnUiThread {
            weather.text = value?.let { "${it.temperature}°${if (Prefs.celsius(this)) "C" else "F"}  ${it.label}" } ?: "Weather unavailable"
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!preview && Prefs.kiosk(this)) Log.i("SamsungLockD2", "GUARDIAN_ACTIVITY_RESUMED")
        if (!::clock.isInitialized) return
        wallpaperActive = true
        wallpaperAnimations.forEach { it.resume() }
        renderNotifications()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            batteryReceiver.onReceive(this, it)
        }
        handler.post(ticker)
        if (!preview && Prefs.quickSettingsGuard(this)) handler.post(quickSettingsGuard)
        if (!preview && !unlocking && Prefs.kiosk(this)) {
            RootKiosk.attach(this) { kioskStatus.text = it }
            KioskD2Guardian.start(this)
        }
        // Fingerprint is an enhanced Guardian path: only offer it while root is
        // actually available. After a normal reboot that loses temporary root,
        // fall back to the configured Guardian PIN/pattern instead of opening a
        // biometric session that depends on the enhanced integration.
        if (!preview && !unlocking && !biometricRunning && app.d2lock.root.RootManager.isAvailable()) {
            startGuardianFingerprint()
        } else if (!preview && !unlocking && !app.d2lock.root.RootManager.isAvailable()) {
            fingerprintGlass?.visibility = View.GONE
        }
        if (!preview && !healthyReported) {
            healthyReported = true
            window.decorView.postDelayed({
                if (!isDestroyed && !isFinishing && hasWindowFocus()) {
                    LockScreenService.markHealthy(this)
                } else healthyReported = false
            }, 1500)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode && !preview && Prefs.kiosk(this)) {
            // A kiosk lock screen must never remain usable as one pane of multi-window.
            Log.w("SamsungLockD2", "GUARDIAN_MULTIWINDOW_DETECTED")
            window.decorView.post {
                if (!isDestroyed && !isFinishing) {
                    RootKiosk.reassert(this, bringToFront = true)
                }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        if (unlocking || GuardianWatchdog.isTrustedAuthenticationActive()) {
            Log.i("SamsungLockD2", "GUARDIAN_NEW_INTENT_DEFERRED_AUTH")
            return
        }
        // Keep the existing singleTask Activity during screen-on/reassert intents.
        // Recreating here races Samsung keyguard and can expose the Bouncer.
        Log.i("SamsungLockD2", "GUARDIAN_NEW_INTENT_REUSED")
        if (!preview && Prefs.kiosk(this) && hasWindowFocus() && RootKiosk.isEnforced()) {
            window.decorView.post { if (!isDestroyed && !isFinishing) RootKiosk.reassert(this) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!preview && Prefs.kiosk(this)) {
            Log.i("SamsungLockD2", if (hasFocus) "GUARDIAN_WINDOW_FOCUS_GAINED" else "GUARDIAN_WINDOW_FOCUS_LOST")
            if (hasFocus && RootKiosk.isEnforced()) {
                window.decorView.post { if (!isDestroyed && !isFinishing) RootKiosk.reassert(this) }
            }
            if (hasFocus && fingerprintRestartPending && !unlocking && !biometricRunning) {
                fingerprintRestartPending = false
                window.decorView.postDelayed({
                    if (!isDestroyed && !isFinishing && hasWindowFocus() && !unlocking && !biometricRunning) {
                        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_RESTART_AFTER_FOCUS")
                        startGuardianFingerprint()
                    } else if (!isDestroyed && !isFinishing && !unlocking) {
                        fingerprintRestartPending = true
                    }
                }, 250)
            }
        }
    }

    override fun onPause() {
        if (!preview && Prefs.kiosk(this)) Log.i("SamsungLockD2", "GUARDIAN_ACTIVITY_PAUSED")
        wallpaperActive = false
        wallpaperAnimations.forEach { it.pause() }
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(quickSettingsGuard)
        runCatching { unregisterReceiver(batteryReceiver) }
        // BiometricPrompt may temporarily move this Activity out of RESUMED state.
        // Keep the active authentication session alive here; explicit teardown happens
        // on destroy/new lock lifecycle instead of racing the system biometric UI.
        super.onPause()
    }

    override fun onStop() {
        if (!preview && Prefs.kiosk(this)) Log.i("SamsungLockD2", "GUARDIAN_ACTIVITY_STOPPED changingConfig=$isChangingConfigurations")
        handler.removeCallbacks(hideBanner)
        hideBanner.run()
        // Keep an active PIN/pattern sheet alive while Android temporarily changes
        // focus (for example call UI). Dismissing it here races Guardian against
        // the user and recreates the fast-unlock bug.
        if (!unlocking && !GuardianWatchdog.isTrustedAuthenticationActive()) {
            // If Samsung Keyguard takes the foreground, ask the companion service to
            // restore the existing Guardian surface instead of recreating this Activity.
            if (!preview && Prefs.kiosk(this)) {
                LockScreenService.surfaceLost(this, "activity_stopped")
            }
            pinDialog?.dismiss()
            pinDialog = null
        }
        super.onStop()
    }

    // API 33+ uses the OnBackInvokedDispatcher registered in onCreate.
    // This override is only the Android 12 fallback (minimum SDK 31).
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Used on Android 12")
    override fun onBackPressed() { if (preview) finish() else authenticate() }

    override fun onDestroy() {
        if (!preview && Prefs.kiosk(this)) Log.i("SamsungLockD2", "GUARDIAN_ACTIVITY_DESTROYED changingConfig=$isChangingConfigurations")
        RootKiosk.detach(this)
        wallpaperAnimations.forEach { it.cancel() }
        wallpaperAnimations.clear()
        wallpaperExecutor.shutdownNow()
        if (NotificationStore.onChanged != null) NotificationStore.onChanged = null
        app.d2lock.notifications.CallNotificationStore.onChanged = null
        NotificationStore.onPosted = null
        // A watchdog/shell reassert can recreate this Activity while Samsung's
        // biometric surface still owns authentication. Do not cancel that session
        // merely because the Activity instance is being replaced.
        if (isChangingConfigurations || fingerprintSessionActive) {
            Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_PRESERVE_ON_DESTROY active=$fingerprintSessionActive changingConfig=$isChangingConfigurations")
        } else {
            cancelGuardianFingerprint("activity_destroyed")
        }
        KioskD2Guardian.stop(this)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (Appearance.mode(this@LockScreenActivity) == 3) intArrayOf(Color.BLACK, Color.BLACK) else intArrayOf(Appearance.blend(Appearance.background(this@LockScreenActivity, true), Appearance.accent(this@LockScreenActivity), .1f), Appearance.background(this@LockScreenActivity, true)))
        }
        Prefs.wallpaper(this)?.let { saved ->
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = if (Prefs.wallpaperAmoled(this@LockScreenActivity)) .58f else .72f
                val zoom = Prefs.wallpaperZoom(this@LockScreenActivity) / 100f
                scaleX = zoom
                scaleY = zoom
                translationX = dp(Prefs.wallpaperOffsetX(this@LockScreenActivity)).toFloat()
                translationY = dp(Prefs.wallpaperOffsetY(this@LockScreenActivity)).toFloat()
                val wallpaperView = this
                wallpaperExecutor.execute {
                    val bitmap = runCatching { WallpaperDecoder.decode(applicationContext, Uri.parse(saved)) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed || isFinishing) {
                            bitmap?.recycle()
                        } else if (bitmap != null) {
                            wallpaperView.setImageBitmap(bitmap)
                            if (Prefs.wallpaperParallax(this@LockScreenActivity)) {
                                listOf(View.SCALE_X, View.SCALE_Y).forEach { property ->
                                    wallpaperAnimations += ObjectAnimator.ofFloat(wallpaperView, property, zoom, zoom + .06f).apply {
                                        duration = 14000
                                        repeatCount = ValueAnimator.INFINITE
                                        repeatMode = ValueAnimator.REVERSE
                                        start()
                                        if (!wallpaperActive) pause()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(this@LockScreenActivity, "Wallpaper could not be opened. Choose another image in settings.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, FrameLayout.LayoutParams(-1, -1))
            val dim = (Prefs.wallpaperDim(this) + if (Prefs.wallpaperAmoled(this)) 18 else 0).coerceAtMost(90)
            if (dim > 0) frame.addView(View(this).apply {
                setBackgroundColor(Color.argb((255f * dim / 100f).toInt(), 0, 0, 0))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }

        if (Prefs.glassShimmer(this)) {
            frame.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.TRANSPARENT, 0x18ffffff, Color.TRANSPARENT))
                alpha = .55f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                ObjectAnimator.ofFloat(this, View.TRANSLATION_X, -dp(180).toFloat(), dp(180).toFloat()).apply {
                    duration = 6500
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                    wallpaperAnimations += this
                    if (!wallpaperActive) pause()
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }
        if (Prefs.doubleTapSleep(this)) {
            frame.setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_UP) false
                else {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val doubleTap = now - emptyTapAt in 40..350
                    emptyTapAt = now
                    if (doubleTap && NotificationStore.items.isEmpty()) {
                        if (Prefs.rootMode(this@LockScreenActivity)) {
                            runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26")) }
                        } else {
                            runCatching { getSystemService(PowerManager::class.java).isInteractive }
                            Toast.makeText(this@LockScreenActivity, "Double-tap sleep requires D2 root mode", Toast.LENGTH_SHORT).show()
                        }
                        true
                    } else false
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(72), dp(20), dp(125))
        }
        val uiScale = Prefs.lockScale(this) / 100f
        val style = Prefs.clockStyle(this)
        val adaptiveFactor = if (Prefs.clockAdaptive(this)) {
            when {
                Prefs.showMedia(this) && NotificationStore.items.isNotEmpty() -> .82f
                NotificationStore.items.isNotEmpty() -> .90f
                else -> 1f
            }
        } else 1f
        val styleFactor = when (style) {
            "minimal" -> .78f
            "condensed" -> .94f
            "bold" -> 1.04f
            else -> 1f
        }
        val clockSize = 82f * (Prefs.clockScale(this) / 100f) * adaptiveFactor * styleFactor
        content.scaleX = uiScale
        content.scaleY = uiScale
        val requestedClockLayout = Prefs.clockLayout(this)
        val stackedClock = requestedClockLayout == "stacked" ||
            (requestedClockLayout == "auto" && Prefs.clockAdaptive(this) &&
                (NotificationStore.items.size >= 2))
        clock = label(if (stackedClock) "12\n00" else "12:00", clockSize, Appearance.text(this, true)).apply {
            if (stackedClock) {
                setLineSpacing(-dp(12).toFloat(), .86f)
                includeFontPadding = false
            }
            gravity = Gravity.CENTER
            typeface = when (style) {
                "rounded" -> android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL)
                "condensed" -> android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                "bold" -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                "minimal" -> android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                "classic" -> android.graphics.Typeface.DEFAULT
                else -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            letterSpacing = when (style) {
                "condensed" -> -.075f
                "minimal" -> .015f
                "bold" -> -.035f
                else -> -.05f
            }
        }
        date = label("", 18f, Appearance.secondary(this, true)).apply {
            gravity = Gravity.CENTER
            visibility = if (Prefs.showDate(this@LockScreenActivity)) View.VISIBLE else View.GONE
        }
        val topInfoScale = Prefs.topInfoSize(this) / 100f
        val topInfo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            weather = label("◌  Loading…", 15f * topInfoScale, Appearance.text(this@LockScreenActivity, true)).apply {
                gravity = Gravity.CENTER
                visibility = if (Prefs.showWeather(this@LockScreenActivity)) View.VISIBLE else View.GONE
            }
            val divider = View(this@LockScreenActivity).apply {
                setBackgroundColor(0x35ffffff)
                visibility = if (Prefs.showWeather(this@LockScreenActivity)) View.VISIBLE else View.GONE
            }
            battery = label("▰  —%", 15f * topInfoScale, Appearance.text(this@LockScreenActivity, true)).apply { gravity = Gravity.CENTER }
            background = Appearance.glass(this@LockScreenActivity, 30f, adaptiveGlass(Prefs.componentGlass(this@LockScreenActivity, "top_info")), true)
            if (Prefs.showWeather(this@LockScreenActivity)) {
                addView(weather, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(divider, LinearLayout.LayoutParams(dp(1), dp(20)))
            }
            addView(battery, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = Appearance.glass(this@LockScreenActivity, 18f, 20, false)
            fun statusIcon(res:Int, desc:String)=ImageView(this@LockScreenActivity).apply {
                setImageResource(res)
                imageTintList=android.content.res.ColorStateList.valueOf(Appearance.text(this@LockScreenActivity,true))
                contentDescription=desc
                setPadding(dp(3),dp(3),dp(3),dp(3))
            }
            addView(statusIcon(app.d2lock.R.drawable.ic_lock_guardian,"Guardian active"),LinearLayout.LayoutParams(dp(24),dp(24)))
            val cm=getSystemService(ConnectivityManager::class.java)
            val caps=runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {
                addView(statusIcon(app.d2lock.R.drawable.ic_lock_wifi,"Wi-Fi connected"),LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginStart=dp(7) })
            }
            val nextAlarm=runCatching { getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()
            if(nextAlarm!=null) {
                addView(statusIcon(app.d2lock.R.drawable.ic_lock_alarm,"Alarm set"),LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginStart=dp(7) })
            }
            isClickable=true
            isFocusable=true
            contentDescription="Guardian lock status. Long press for diagnostics."
            setOnLongClickListener { showGuardianDiagnostics(); true }
        }
        content.addView(statusRow,LinearLayout.LayoutParams(-2,dp(34)).apply { bottomMargin=dp(7) })
        content.addView(clock)
        content.addView(date)
        kioskStatus = label(if (preview) "Preview • unlocked" else if (Prefs.kiosk(this)) "Starting root kiosk…" else "App-only mode • Home/Recents can exit", 12f, Appearance.text(this, true)).apply {
            gravity = Gravity.CENTER
        }
        content.addView(kioskStatus)
        if (preview) {
            val taps = DoubleTap()
            content.addView(actionButton("Double-tap to lock D2") {
                if (taps.tap(android.os.SystemClock.elapsedRealtime())) {
                    if (PinStore(this).configured()) {
                        intent.putExtra("preview", false)
                        recreate()
                    } else Toast.makeText(this, "Create your D2 PIN in settings first.", Toast.LENGTH_LONG).show()
                }
            })
            content.addView(actionButton("Close preview") { finish() })
        }
        content.addView(topInfo, LinearLayout.LayoutParams(-1, dp((54 * topInfoScale).toInt())))
        // Weather source details are kept in app credits so the lock screen stays clean.

        notifications = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val notificationScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(notifications, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(notificationScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val mediaLayout = Prefs.mediaLayout(this)
        val mediaCompact = mediaLayout == "compact"
        val mediaLarge = mediaLayout == "large"
        mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (mediaCompact) 12 else 16), dp(if (mediaCompact) 10 else 14), dp(if (mediaCompact) 12 else 16), dp(if (mediaCompact) 10 else 14))
            background = Appearance.glass(this@LockScreenActivity, 32f, adaptiveGlass(Prefs.componentGlass(this@LockScreenActivity, "media")), true)
            elevation = dp(10).toFloat()
        }
        mediaHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaArtwork = ImageView(this).apply {
            setImageResource(app.d2lock.R.drawable.ic_media_music)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(if (mediaCompact) 14 else 18).toFloat()
                setColor(0xff41475d.toInt())
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            contentDescription = "Album artwork"
        }
        val artSize = dp(if (mediaCompact) 48 else if (mediaLarge) 76 else 64)
        mediaHeader.addView(mediaArtwork, LinearLayout.LayoutParams(artSize, artSize))
        val mediaDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            mediaTitle = label("Media", if (mediaCompact) 14f else if (mediaLarge) 19f else 17f, Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            mediaArtist = label("Play music to show it here", if (mediaCompact) 11f else 13f, 0xffd1d4df.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            mediaTime = label("Media session", 11f, 0xffb9bdc9.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
            addView(mediaTitle)
            addView(mediaArtist)
            addView(mediaTime)
        }
        mediaHeader.addView(mediaDetails, LinearLayout.LayoutParams(0, -2, 1f))
        mediaPanel.addView(mediaHeader)
        mediaProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44ffffff)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        mediaPanel.addView(mediaProgress, LinearLayout.LayoutParams(-1, dp(3)).apply {
            topMargin = dp(if (mediaCompact) 8 else 11)
            bottomMargin = dp(if (mediaCompact) 7 else 9)
        })
        mediaControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(mediaIconButton(app.d2lock.R.drawable.ic_media_previous, "Previous") {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); media.previous()
            })
            playPause = mediaButton("▶", true) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); media.toggle()
            }
            addView(playPause, LinearLayout.LayoutParams(dp(if (mediaCompact) 50 else 58), dp(if (mediaCompact) 46 else 54)).apply {
                marginStart=dp(10); marginEnd=dp(10)
            })
            addView(mediaIconButton(app.d2lock.R.drawable.ic_media_next, "Next") {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); media.next()
            })
            val buttonScale = Prefs.mediaButtonsScale(this@LockScreenActivity) / 100f
            scaleX = buttonScale
            scaleY = buttonScale
        }
        mediaPanel.addView(mediaControls, LinearLayout.LayoutParams(-1, -2))
        if (Prefs.showMedia(this)) content.addView(mediaPanel,
            LinearLayout.LayoutParams(-1, dp(if (mediaCompact) 126 else if (mediaLarge) 174 else 154)).apply { bottomMargin = dp(12) })
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))

        chargingOverlay = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            visibility=View.GONE
            setPadding(dp(22),dp(14),dp(22),dp(14))
            background=Appearance.glass(this@LockScreenActivity,30f,54,true)
            elevation=dp(22).toFloat()
            addView(ImageView(this@LockScreenActivity).apply {
                setImageResource(app.d2lock.R.drawable.ic_lock_battery)
                imageTintList=android.content.res.ColorStateList.valueOf(Appearance.accent(this@LockScreenActivity))
                contentDescription=null
            },LinearLayout.LayoutParams(dp(28),dp(28)).apply { gravity=Gravity.CENTER_HORIZONTAL })
            addView(label("Charging",16f,Appearance.text(this@LockScreenActivity,true)).apply { gravity=Gravity.CENTER })
        }
        frame.addView(chargingOverlay,FrameLayout.LayoutParams(dp(210),dp(92),Gravity.CENTER))

        // Guardian fingerprint glass: this is D2's visual layer. The secure
        // authentication itself remains Android/Samsung BIOMETRIC_STRONG.
        if (!preview && app.d2lock.root.RootManager.isAvailable()) {
            fingerprintGlass = buildFingerprintGlass().also { glass ->
                frame.addView(glass, FrameLayout.LayoutParams(dp(228), dp(58), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    // Samsung test device reports FOD center at y=2410 on a 3120px panel.
                    // Scale the normalized location so the D2 visual follows resolution changes.
                    topMargin = ((resources.displayMetrics.heightPixels * .772f) - dp(29)).toInt().coerceAtLeast(dp(120))
                })
            }
        }

        val floatingBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = Appearance.floatingBar(this@LockScreenActivity)
            elevation = dp(16).toFloat()
            addView(shortcutButton("left"), LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(TextView(this@LockScreenActivity).apply {
                text = "◆  " + if (Prefs.unlockMethod(this@LockScreenActivity) == "pattern") "PATTERN" else "PIN"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Unlock D2"
                background = Appearance.glass(this@LockScreenActivity, 26f, 34, true)
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); authenticate() }
                setOnTouchListener(unlockSwipeListener())
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart=dp(12); marginEnd=dp(12) })
            addView(shortcutButton("right"), LinearLayout.LayoutParams(dp(58), dp(58)))
        }
        val barParams = FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        frame.addView(floatingBar, barParams)
        fun positionBar(width: Int, inset: Int) {
            val available = (width - dp(32)).coerceAtLeast(1)
            barParams.width = (available * Appearance.barWidth(this) / 100).coerceAtLeast(dp(190)).coerceAtMost(available)
            barParams.bottomMargin = dp(Appearance.barGap(this)) + inset
            floatingBar.layoutParams = barParams
            content.setPadding(dp(20), dp(72), dp(20), barParams.bottomMargin + dp(96))
        }
        frame.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) positionBar(right - left, frame.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0)
        }
        frame.setOnApplyWindowInsetsListener { _, insets ->
            positionBar(frame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            insets
        }
        liveBanner = label("", 15f, Appearance.text(this, true)).apply {
            visibility = View.GONE
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = notificationPanel(null, banner = true)
            setShadowLayer(if (Appearance.dark(this@LockScreenActivity, true)) dp(2).toFloat() else 0f, 0f, dp(1).toFloat(), 0xcc000000.toInt())
            elevation = dp(20).toFloat()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        frame.addView(liveBanner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(18), dp(36), dp(18), 0)
        })
        return frame
    }

    private fun unlockSwipeListener() = object : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            val mode = Prefs.floatingUnlockGesture(this@LockScreenActivity)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y; dragging = false
                    v.animate().cancel()
                    v.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.88f).setDuration(90).start()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    val valid = horizontal || dy < 0f
                    val distance = if (horizontal) kotlin.math.abs(dx) else (-dy).coerceAtLeast(0f)
                    if (valid && distance > dp(8)) dragging = true
                    if (mode != "tap_only" && valid) {
                        val travel = distance.coerceAtMost(dp(48).toFloat())
                        if (horizontal) { v.translationX = dx.coerceIn(-dp(48).toFloat(), dp(48).toFloat()) * .55f; v.translationY = 0f }
                        else { v.translationY = -travel * .55f; v.translationX = 0f }
                        val scale = .97f + (travel / dp(48)) * .06f
                        v.scaleX = scale; v.scaleY = scale
                        v.alpha = .88f + (travel / dp(48)) * .12f
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val slide = kotlin.math.abs(dx) > dp(36) || dy < -dp(36)
                    val tap = !dragging && kotlin.math.abs(dx) < dp(12) && kotlin.math.abs(dy) < dp(12)
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
                    val unlock = when (mode) { "slide_only" -> slide; "tap_only" -> tap; else -> slide || tap }
                    if (unlock) { v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); authenticate() }
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
                    return true
                }
            }
            return false
        }
    }

    private fun applyAdaptiveClockPosition(stacked: Boolean) {
        if (!::clock.isInitialized) return
        val pressure = NotificationStore.items.size.coerceAtMost(4)
        val mediaActive = ::media.isInitialized && media.isPlaying()
        val target = if (Prefs.clockAdaptive(this)) {
            when {
                mediaActive && pressure >= 2 -> -dp(26).toFloat()
                mediaActive || pressure >= 3 -> -dp(18).toFloat()
                pressure >= 1 -> -dp(10).toFloat()
                else -> 0f
            }
        } else 0f
        clock.animate().translationY(target).setDuration(220).start()
        if (::date.isInitialized) date.animate().translationY(target).setDuration(220).start()
        clock.setLineSpacing(if (stacked) -dp(12).toFloat() else 0f, if (stacked) .86f else 1f)
    }

    private fun renderNotifications() {
        bannerKey?.let { key ->
            val current = NotificationStore.items.firstOrNull { it.key == key }
            val text = current?.let { app.d2lock.notifications.NotificationPresentation.banner(it, Prefs.notificationPrivacy(this)) }
            if (text == null) hideBanner.run()
            else {
                liveBanner.text = text
                liveBanner.background = notificationPanel(if (Prefs.notificationPrivacy(this) == 1) null else current.packageName, banner = true)
                liveBanner.setOnClickListener { openNotification(current) }
            }
        }
        notifications.removeAllViews()
        renderCallControls()
        val privacy = if (preview) 4 else Prefs.notificationPrivacy(this)
        if (privacy == 0) return
        val listenerEnabled = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, LockNotificationListener::class.java))
        if (!listenerEnabled) {
            notifications.addView(label("Allow notification access in D2 settings", 14f, Appearance.text(this, true)).apply {
                gravity = Gravity.CENTER
            })
            return
        }
        val visibleItems = NotificationStore.items.filter {
            preview || it.visibility != Notification.VISIBILITY_SECRET
        }
        if (visibleItems.isEmpty()) {
            // Keep the lock screen visually clean when there is nothing to show.
            // The notification container simply remains empty.
            return
        }
        if (privacy == 1) {
            notifications.addView(label("${visibleItems.size} notifications", 15f, Appearance.text(this, true)).apply {
                gravity = Gravity.CENTER
            })
            return
        }
        applyAdaptiveClockPosition(Prefs.clockLayout(this) == "stacked" ||
            (Prefs.clockLayout(this) == "auto" && Prefs.clockAdaptive(this) &&
                ((::media.isInitialized && media.isPlaying()) || visibleItems.size >= 2)))
        visibleItems.forEach { item ->
            val density = Prefs.notificationDensity(this)
            val adaptiveStack = Prefs.experimentalAdaptiveNotifications(this) && visibleItems.size >= 3
            val horizontalPad = when {
                adaptiveStack -> 13
                density == "compact" -> 13
                density == "large" -> 18
                else -> 16
            }
            val verticalPad = when {
                adaptiveStack && visibleItems.size >= 5 -> 6
                adaptiveStack -> 8
                density == "compact" -> 7
                density == "large" -> 14
                else -> 11
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(horizontalPad), dp(verticalPad), dp(horizontalPad), dp(verticalPad))
                background = Appearance.glass(this@LockScreenActivity, Prefs.notificationRadius(this@LockScreenActivity).toFloat(), adaptiveGlass(Prefs.notificationGlass(this@LockScreenActivity)), true)
                addView(notificationLabel(item.app, 11f).apply {
                    alpha = .78f
                    setPadding(0, 0, 0, dp(2))
                })
                if (privacy == 4 || (privacy == 3 && item.visibility == Notification.VISIBILITY_PUBLIC)) {
                    addView(notificationLabel(item.title.ifBlank { item.text }, 16f).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    if (item.title.isNotBlank() && item.text.isNotBlank()) {
                        addView(notificationLabel(item.text, 13f).apply {
                            alpha = .88f
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                }
            }
            if (item.contentIntent != null) card.setOnClickListener { openNotification(item) }
            card.setOnTouchListener(notificationDismissListener(item, card))
            notifications.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(when (density) { "compact" -> 6; "large" -> 12; else -> 9 })
            })
        }
    }

    private fun notificationDismissListener(item: app.d2lock.notifications.LockNotification, card: View) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var tracker: VelocityTracker? = null
        private var thresholdHaptic = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    thresholdHaptic = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dx = event.x - downX
                    if (kotlin.math.abs(dx) > dp(2)) {
                        card.translationX = dx
                        card.alpha = (1f - kotlin.math.abs(dx) / (card.width.coerceAtLeast(1) * .55f)).coerceIn(.18f, 1f)
                        val crossed = kotlin.math.abs(dx) > card.width * .055f
                        if (crossed && !thresholdHaptic) {
                            if (Prefs.experimentalEnhancedHaptics(this@LockScreenActivity)) {
                                card.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                            thresholdHaptic = true
                        } else if (!crossed) {
                            thresholdHaptic = false
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocityX = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // One UI-style forgiving dismissal: a short deliberate drag or a quick flick
                    // in either direction should dismiss without requiring a slow full-width swipe.
                    if (kotlin.math.abs(dx) > card.width * .055f || (kotlin.math.abs(dx) > dp(5) && kotlin.math.abs(velocityX) > dp(190))) {
                        card.animate().translationX(if (dx >= 0f) card.width.toFloat() else -card.width.toFloat()).alpha(0f).setDuration(120).withEndAction {
                            NotificationStore.listener?.dismiss(item.key)
                            NotificationStore.items.removeAll { it.key == item.key }
                            renderNotifications()
                        }.start()
                    } else {
                        card.animate().translationX(0f).alpha(1f).setDuration(140).start()
                        if (kotlin.math.abs(dx) < dp(12) && kotlin.math.abs(dy) < dp(12) && item.contentIntent != null) openNotification(item)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null; card.animate().translationX(0f).alpha(1f).setDuration(120).start(); return true }
            }
            return false
        }
    }
    private fun renderCallControls() {
        app.d2lock.notifications.CallNotificationStore.items.forEach { call ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = notificationPanel(null, call = true)
                addView(notificationLabel("Phone call", 17f))
            }
            fun addControl(title: String, pending: android.app.PendingIntent) {
                card.addView(actionButton(title) {
                    try { app.d2lock.notifications.CallNotificationStore.send(this, pending) }
                    catch (_: Exception) {
                        Toast.makeText(this, "Call control unavailable. Open the phone call or unlock D2.", Toast.LENGTH_LONG).show()
                    }
                }.apply { setShadowLayer(if (Appearance.dark(this@LockScreenActivity, true)) dp(2).toFloat() else 0f, 0f, dp(1).toFloat(), 0xcc000000.toInt()) })
            }
            call.controls.forEach { addControl(it.label, it.intent) }
            call.open?.let { addControl("Open phone call", it) }
            notifications.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showLiveNotification(item: app.d2lock.notifications.LockNotification) {
        if (preview || !hasWindowFocus() || !Prefs.liveNotifications(this)) return
        val text = app.d2lock.notifications.NotificationPresentation.banner(item, Prefs.notificationPrivacy(this)) ?: return
        bannerKey = item.key
        liveBanner.text = text
        liveBanner.background = notificationPanel(if (Prefs.notificationPrivacy(this) == 1) null else item.packageName, banner = true)
        liveBanner.visibility = View.VISIBLE
        liveBanner.setOnClickListener { openNotification(item) }
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 8000)
    }

    private fun openNotification(item: app.d2lock.notifications.LockNotification) {
        val pending = item.contentIntent ?: return
        // Message intents are never sent while kiosk is locked; verify PIN and release first.
        authenticate {
            try { app.d2lock.notifications.CallNotificationStore.send(this, pending) }
            catch (_: Exception) { Toast.makeText(this, "Notification is no longer available", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun buildFingerprintGlass(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = Appearance.glass(this@LockScreenActivity, 24f, 34, true)
        elevation = dp(14).toFloat()
        alpha = .94f
        contentDescription = "Fingerprint unlock. Swipe left or right to dismiss."

        fingerprintGlyph = TextView(this@LockScreenActivity).apply {
            text = "◎"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Appearance.text(this@LockScreenActivity, true))
            contentDescription = "Fingerprint sensor"
        }
        addView(fingerprintGlyph, LinearLayout.LayoutParams(dp(42), dp(42)))

        fingerprintLoading = ProgressBar(
            this@LockScreenActivity,
            null,
            android.R.attr.progressBarStyleSmall
        ).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Starting fingerprint"
        }
        addView(fingerprintLoading, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            marginStart = dp(4)
            marginEnd = dp(6)
        })

        fingerprintStatus = label("Touch sensor", 13f, Appearance.text(this@LockScreenActivity, true)).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(fingerprintStatus, LinearLayout.LayoutParams(0, dp(42), 1f))

        addView(label("×", 22f, Appearance.secondary(this@LockScreenActivity, true)).apply {
            gravity = Gravity.CENTER
            contentDescription = "Dismiss fingerprint"
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissFingerprintGlass("close_button") }
        }, LinearLayout.LayoutParams(dp(36), dp(42)))

        var downX = 0f
        var downY = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    view.translationX = dx
                    view.alpha = (1f - kotlin.math.abs(dx) / dp(220).toFloat()).coerceIn(.35f, .94f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) >= dp(72) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        view.animate().translationX(if (dx < 0) -width.toFloat() else width.toFloat())
                            .alpha(0f).setDuration(160).withEndAction {
                                dismissFingerprintGlass("swipe")
                            }.start()
                    } else {
                        view.animate().translationX(0f).alpha(.94f).setDuration(140).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dismissFingerprintGlass(reason: String) {
        fingerprintDismissed = true
        cancelGuardianFingerprint("guardian_dismissed_$reason")
        fingerprintGlass?.animate()?.cancel()
        fingerprintGlass?.visibility = View.GONE
        GuardianWatchdog.endTrustedAuthentication("biometric_dismissed")
        window.decorView.post {
            if (!isFinishing && !isDestroyed) window.decorView.requestFocus()
        }
        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_DISMISSED reason=$reason")
    }

    private fun setFingerprintGlassState(state: String) {
        val status = fingerprintStatus ?: return
        val glyph = fingerprintGlyph ?: return
        fingerprintPulse?.cancel()
        fingerprintPulse = null
        fingerprintLoading?.visibility = if (state == "starting") View.VISIBLE else View.GONE
        glyph.visibility = if (state == "starting") View.GONE else View.VISIBLE
        when (state) {
            "starting" -> {
                status.text = "Starting fingerprint…"
                Log.i("SamsungLockD2", "GUARDIAN_ONEUI_PROGRESS state=fingerprint_starting")
            }
            "scanning" -> {
                status.text = "Touch fingerprint sensor"
                glyph.alpha = 1f
                fingerprintPulse = ObjectAnimator.ofFloat(glyph, View.SCALE_X, .90f, 1.10f, .90f).apply {
                    duration = 1250
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
                ObjectAnimator.ofFloat(glyph, View.SCALE_Y, .90f, 1.10f, .90f).apply {
                    duration = 1250
                    repeatCount = ValueAnimator.INFINITE
                    start()
                    fingerprintPulse?.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) { cancel() }
                    })
                }
            }
            "failed" -> {
                status.text = "Not recognized • try again"
                glyph.animate().rotationBy(8f).setDuration(70).withEndAction {
                    glyph.animate().rotation(0f).setDuration(110).start()
                }.start()
            }
            "success" -> {
                status.text = "Fingerprint verified"
                glyph.text = "✓"
                glyph.animate().scaleX(1.16f).scaleY(1.16f).setDuration(120).start()
            }
            "unavailable" -> {
                status.text = "Fingerprint unavailable"
                glyph.alpha = .55f
            }
            "dismissed" -> {
                status.text = "Fingerprint dismissed"
                glyph.alpha = .55f
            }
        }
    }

    private fun startGuardianFingerprint() {
        if (preview || unlocking || biometricRunning || fingerprintDismissed || isFinishing || isDestroyed) return
        synchronized(fingerprintSessionLock) {
            if (fingerprintSessionActive) {
                Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_SUPPRESS_DUPLICATE source=start")
                return
            }
            fingerprintSessionActive = true
            ownsBiometricSession = true
        }

        val manager = getSystemService(BiometricManager::class.java)
        val status = manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_CAN_AUTH status=$status")
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            clearFingerprintSession("unavailable")
            setFingerprintGlassState("unavailable")
            return
        }

        val cancel = CancellationSignal()
        biometricCancel = cancel
        biometricRunning = true
        // Samsung's BiometricPrompt intentionally owns foreground focus while
        // authentication is active. Tell Guardian before launching it so kiosk
        // recovery does not fight the trusted system surface.
        GuardianWatchdog.beginTrustedAuthentication()
        Log.i("SamsungLockD2", "GUARDIAN_BIOMETRIC_HANDOFF_BEGIN focus=" + hasWindowFocus())
        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_REQUESTED")
        setFingerprintGlassState("starting")
        window.decorView.postDelayed({
            if (biometricRunning && !unlocking && !isFinishing && !isDestroyed) {
                setFingerprintGlassState("scanning")
            }
        }, 180)
        // Samsung renders its own secure biometric surface. Hide Guardian's
        // fingerprint glass while that prompt owns authentication so we never
        // stack two fingerprint UIs on top of each other.
        fingerprintGlass?.visibility = View.INVISIBLE

        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Kiosk D2 Guardian")
            .setSubtitle("Fingerprint unlock")
            .setDescription("Use an enrolled fingerprint or cancel to use your Guardian PIN/pattern.")
            .setNegativeButton("Use PIN / pattern", mainExecutor) { _, _ ->
                // Treat explicit fallback as dismissal for this lock session so
                // focus recovery cannot immediately reopen Samsung's prompt.
                fingerprintDismissed = true
                fingerprintRestartPending = false
                biometricRunning = false
                biometricCancel = null
                clearFingerprintSession("fallback")
                GuardianWatchdog.endTrustedAuthentication("biometric_fallback")
                Log.i("SamsungLockD2", "GUARDIAN_BIOMETRIC_HANDOFF_END reason=fallback")
                Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_FALLBACK")
                fingerprintGlass?.visibility = View.VISIBLE
                if (!isFinishing && !isDestroyed) {
                    window.decorView.post {
                        window.decorView.requestFocus()
                        authenticate()
                    }
                }
            }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                if (!biometricRunning || unlocking || isFinishing || isDestroyed) return
                biometricRunning = false
                biometricCancel = null
                clearFingerprintSession("success")
                unlocking = true
                handler.removeCallbacks(quickSettingsGuard)
                val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                Log.i(
                    "SamsungLockD2",
                    "GUARDIAN_743_HANDOFF_BEGIN type=${result.authenticationType} keyguardLocked=${keyguard?.isKeyguardLocked} deviceLocked=${keyguard?.isDeviceLocked} focus=${hasWindowFocus()}"
                )
                Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_SUCCESS type=${result.authenticationType}")
                setFingerprintGlassState("success")
                GuardianWatchdog.endTrustedAuthentication("auth_success")
                Log.i("SamsungLockD2", "GUARDIAN_BIOMETRIC_HANDOFF_END reason=success")
                playUnlockHaptic()
                RootKiosk.unlock(this@LockScreenActivity) {
                    app.d2lock.bridge.IslandBridge.setLocked(this@LockScreenActivity, false)
                    finish()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_FAILED")
                setFingerprintGlassState("failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricRunning = false
                biometricCancel = null
                clearFingerprintSession("error_$errorCode")
                Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_ERROR code=$errorCode message=$errString")
                GuardianWatchdog.endTrustedAuthentication("biometric_error_$errorCode")
                Log.i("SamsungLockD2", "GUARDIAN_BIOMETRIC_HANDOFF_END reason=error_$errorCode")
                fingerprintRestartPending = false
                if (!unlocking) {
                    // System Back/gesture-back and Samsung prompt cancellation arrive
                    // here as a biometric error. Keep fingerprint dismissed for this
                    // lock session so Guardian cannot trap the user in a reopen loop.
                    val userCancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    if (userCancelled) {
                        fingerprintDismissed = true
                        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_USER_DISMISSED code=$errorCode")
                    }
                    // A cancelled/failed Samsung prompt must settle back onto D2
                    // rather than immediately reopening and creating a focus loop.
                    setFingerprintGlassState(if (fingerprintDismissed) "dismissed" else "unavailable")
                    fingerprintGlass?.visibility = if (fingerprintDismissed) View.GONE else View.VISIBLE
                    window.decorView.post {
                        if (!isDestroyed && !isFinishing) window.decorView.requestFocus()
                    }
                }
            }
        })
    }

    private fun clearFingerprintSession(reason: String) {
        if (ownsBiometricSession) {
            synchronized(fingerprintSessionLock) { fingerprintSessionActive = false }
            ownsBiometricSession = false
            Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_SESSION_CLEARED reason=$reason")
        }
    }

    private fun cancelGuardianFingerprint(reason: String) {
        if (!biometricRunning && biometricCancel == null) return
        Log.i("SamsungLockD2", "GUARDIAN_FINGERPRINT_CANCEL reason=$reason")
        biometricRunning = false
        biometricCancel?.cancel()
        biometricCancel = null
        clearFingerprintSession(reason)
        GuardianWatchdog.endTrustedAuthentication("biometric_cancel_$reason")
        Log.i("SamsungLockD2", "GUARDIAN_BIOMETRIC_HANDOFF_END reason=cancel_$reason")
        fingerprintGlass?.visibility = View.VISIBLE
    }

    private fun authenticate(afterUnlock: (() -> Unit)? = null) {
        if (preview) { finish(); return }
        if (pinDialog?.isShowing == true) return

        // Authentication grace window: mark the credential UI trusted before it
        // takes focus so kiosk/watchdog recovery cannot race PIN or pattern input.
        unlocking = true
        GuardianWatchdog.beginTrustedAuthentication()
        handler.removeCallbacks(quickSettingsGuard)

        val cancelled = {
            unlocking = false
            GuardianWatchdog.endTrustedAuthentication("auth_cancelled")
            pinDialog = null
            if (!preview && Prefs.quickSettingsGuard(this)) handler.post(quickSettingsGuard)
            if (!preview && Prefs.kiosk(this)) {
                RootKiosk.attach(this) { kioskStatus.text = it }
            }
        }
        val unlocked = {
            GuardianWatchdog.endTrustedAuthentication("auth_success")
            playUnlockHaptic()
            RootKiosk.unlock(this) {
                app.d2lock.bridge.IslandBridge.setLocked(this, false)
                afterUnlock?.invoke()
                finish()
            }
        }
        if (Prefs.unlockMethod(this) == "pattern" && PatternStore(this).configured()) {
            pinDialog = PatternUi.show(this, success = unlocked, usePin = {
                // Keep the grace window active while switching credential methods.
                pinDialog = PinUi.show(this, success = unlocked, cancel = cancelled)
            }, cancel = cancelled)
        } else {
            pinDialog = PinUi.show(this, success = unlocked, cancel = cancelled)
        }
    }

    private fun playUnlockHaptic() {
        val duration = when (Prefs.unlockHaptics(this)) {
            "soft" -> 18L
            "medium" -> 32L
            "strong" -> 50L
            else -> return
        }
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    private fun openCamera() = runCatching {
        startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show() }

    private fun toggleTorch() {
        runCatching {
            val manager = getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.first { cameraId ->
                manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            torchOn = !torchOn
            manager.setTorchMode(id, torchOn)
        }.onFailure { Toast.makeText(this, "Flashlight unavailable", Toast.LENGTH_SHORT).show() }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionButton(value: String, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(Appearance.text(this@LockScreenActivity, true)); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun roundButton(value: String, click: () -> Unit) = TextView(this).apply {
        text = value; textSize = 24f; gravity = Gravity.CENTER; background = glassPanel(32f)
        setTextColor(Color.WHITE)
        setOnClickListener { click() }
    }

    private fun showChargingOverlay(level:Int) {
        val overlay=chargingOverlay ?: return
        chargingOverlayHide?.let { handler.removeCallbacks(it) }
        (overlay.getChildAt(1) as? TextView)?.text="$level%  •  Charging"
        overlay.visibility=View.VISIBLE
        overlay.alpha=0f
        overlay.scaleX=.92f
        overlay.scaleY=.92f
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        chargingOverlayHide=Runnable {
            overlay.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(240).withEndAction {
                overlay.visibility=View.GONE
                overlay.translationY=0f
            }.start()
        }.also { handler.postDelayed(it,2600) }
    }

    private fun showGuardianDiagnostics() {
        val root = app.d2lock.root.RootManager.isAvailable()
        val shizuku = Prefs.shizukuEnabled(this) &&
            runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val biometric = getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

        fun diagnosticRow(label: String, value: String, ready: Boolean? = null) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0x18ffffff)
                    setStroke(dp(1), 0x28ffffff)
                }

                addView(TextView(this@LockScreenActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(0xbfffffff.toInt())
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, -2, 1f))

                addView(TextView(this@LockScreenActivity).apply {
                    text = when (ready) {
                        true -> "●  $value"
                        false -> "○  $value"
                        null -> value
                    }
                    textSize = 13f
                    setTextColor(
                        when (ready) {
                            true -> 0xffd8ffd8.toInt()
                            false -> 0xffd0d0d0.toInt()
                            null -> Color.WHITE
                        }
                    )
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = Gravity.END
                })
            }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))

            addView(TextView(this@LockScreenActivity).apply {
                text = "Guardian Diagnostics"
                textSize = 21f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(TextView(this@LockScreenActivity).apply {
                text = "Live D2 system status"
                textSize = 12f
                setTextColor(0x99ffffff.toInt())
                setPadding(0, dp(3), 0, dp(14))
            })

            val rows = listOf(
                diagnosticRow("D2 surface", "ACTIVE", true),
                diagnosticRow("Kiosk", if (Prefs.kiosk(this@LockScreenActivity)) "ON" else "OFF", Prefs.kiosk(this@LockScreenActivity)),
                diagnosticRow("Root", if (root) "READY" else "OFF", root),
                diagnosticRow("Shizuku", if (shizuku) "READY" else "OFF", shizuku),
                diagnosticRow("LSPosed", if (Prefs.xposedMaster(this@LockScreenActivity)) "ENABLED" else "OFF", Prefs.xposedMaster(this@LockScreenActivity)),
                diagnosticRow("Fingerprint", if (biometric) "READY" else "NOT AVAILABLE", biometric),
                diagnosticRow("Unlock", Prefs.unlockMethod(this@LockScreenActivity).uppercase())
            )
            rows.forEachIndexed { index, row ->
                addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(6)
                })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setPositiveButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.apply {
                setDimAmount(0.42f)
                setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = dp(30).toFloat()
                    setColor(0xe61a1a1a.toInt())
                    setStroke(dp(1), 0x45ffffff)
                })
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                text = "CLOSE"
                setTextColor(0xffd6b2ff.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        }
        dialog.show()
    }

    private fun glassIconButton(icon: Int, description: String, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x2affffff)
            setStroke(dp(1), 0x66ffffff)
        }
        elevation = dp(10).toFloat()
        isClickable = true
        isFocusable = true
        setOnTouchListener { v,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.90f).scaleY(.90f).alpha(.78f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
            }
            false
        }
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            click()
        }
    }

    private fun mediaIconButton(icon: Int, description: String, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x24ffffff)
            setStroke(dp(1), 0x52ffffff)
        }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
        isClickable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            click()
        }
    }

    private fun mediaButton(value: String, primary: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = value; textSize = if (primary) 27f else 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (primary) 0x32ffffff else 0x24ffffff)
            setStroke(dp(1), if (primary) 0x66ffffff else 0x52ffffff)
        }
        layoutParams = LinearLayout.LayoutParams(if (primary) dp(58) else dp(46), if (primary) dp(58) else dp(46)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }
        setOnClickListener { click() }
    }
    private fun shortcutButton(side: String): View = when (Prefs.shortcut(this, side)) {
        "Camera" -> glassIconButton(app.d2lock.R.drawable.ic_lock_camera, "Camera, D2 authentication required") {
            if (preview) openCamera() else authenticate { openCamera() }
        }
        "Flashlight" -> glassIconButton(app.d2lock.R.drawable.ic_lock_flashlight, "Flashlight") { toggleTorch() }
        else -> {
            val value = Prefs.shortcut(this, side)
            if (!value.startsWith("app:")) View(this) else {
                val pkg = value.removePrefix("app:")
                val appInfo = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                if (appInfo == null) View(this) else roundButton("↗") {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (preview) startActivity(launch) else authenticate { startActivity(launch) }
                    }
                }.apply { contentDescription = packageManager.getApplicationLabel(appInfo).toString() }
            }
        }
    }

    private fun notificationLabel(value: String, size: Float) = label(value, size, Appearance.text(this, true)).apply {
        setShadowLayer(if (Appearance.dark(this@LockScreenActivity, true)) dp(2).toFloat() else 0f, 0f, dp(1).toFloat(), 0xcc000000.toInt())
    }

    private fun notificationPanel(app: String?, banner: Boolean = false, call: Boolean = false) =
        Appearance.panel(this, app, banner, call)

    private fun adaptiveGlass(base: Int): Int =
        if (Prefs.experimentalAdaptiveGlass(this)) (base + 16).coerceAtMost(100) else base

    private fun glassPanel(radius: Float = 28f) =
        Appearance.glass(this, radius, adaptiveGlass(36), true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
