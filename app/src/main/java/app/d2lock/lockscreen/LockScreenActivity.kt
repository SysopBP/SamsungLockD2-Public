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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var media: MediaControllerBridge
    private var torchOn = false
    private var preview = false
    private var unlocking = false
    private var healthyReported = false
    private lateinit var kioskStatus: TextView
    private var pinDialog: AlertDialog? = null
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
            mediaTitle.text = media.title().ifBlank { "Media" }
            mediaArtist.text = media.artist().ifBlank { "Play music to show it here" }
            playPause.text = if (media.isPlaying()) "Ⅱ" else "▶"
            val mediaName = media.title()
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
            battery.text = if (charging) "▰  ⚡$percent" else "▰$percent"
            if (charging && level >= 0) {
                LiveHubStore.publish(LiveHubCard("charging", LiveHubKind.CHARGING, "Charging", "$level%", level / 100f))
            } else LiveHubStore.remove("charging")
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
        setContentView(buildUi())
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
        recreate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!preview && Prefs.kiosk(this)) {
            Log.i("SamsungLockD2", if (hasFocus) "GUARDIAN_WINDOW_FOCUS_GAINED" else "GUARDIAN_WINDOW_FOCUS_LOST")
            if (hasFocus && RootKiosk.isEnforced()) {
                window.decorView.post { if (!isDestroyed && !isFinishing) RootKiosk.reassert(this) }
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
        super.onPause()
    }

    override fun onStop() {
        if (!preview && Prefs.kiosk(this)) Log.i("SamsungLockD2", "GUARDIAN_ACTIVITY_STOPPED changingConfig=$isChangingConfigurations")
        handler.removeCallbacks(hideBanner)
        hideBanner.run()
        pinDialog?.dismiss()
        pinDialog = null
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
            background = Appearance.glass(this@LockScreenActivity, 30f, Prefs.componentGlass(this@LockScreenActivity, "top_info"), true)
            if (Prefs.showWeather(this@LockScreenActivity)) {
                addView(weather, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(divider, LinearLayout.LayoutParams(dp(1), dp(20)))
            }
            addView(battery, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
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
        val mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (mediaCompact) 10 else 14), dp(if (mediaCompact) 8 else if (mediaLarge) 15 else 12), dp(10), dp(if (mediaCompact) 8 else if (mediaLarge) 15 else 12))
            background = Appearance.glass(this@LockScreenActivity, 34f, Prefs.componentGlass(this@LockScreenActivity, "media"), true)
            elevation = dp(8).toFloat()
        }
        mediaPanel.addView(label("♫", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0xff41475d.toInt())
            }
        }, LinearLayout.LayoutParams(dp(if (mediaCompact) 48 else if (mediaLarge) 70 else 62), dp(if (mediaCompact) 48 else if (mediaLarge) 70 else 62)))
        val mediaDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (mediaCompact) 9 else 12), 0, 0, 0)
            mediaTitle = label("Media", if (mediaCompact) 14f else if (mediaLarge) 18f else 16f, Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            mediaArtist = label("Play music to show it here", if (mediaCompact) 11f else if (mediaLarge) 13f else 12f, 0xffd1d4df.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(mediaTitle)
            addView(mediaArtist)
        }
        mediaPanel.addView(mediaDetails, LinearLayout.LayoutParams(0, -2, 1f))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaButton("◀|") { media.previous() })
            playPause = mediaButton("▶", true) { media.toggle() }
            addView(playPause)
            addView(mediaButton("|▶") { media.next() })
            val buttonScale = Prefs.mediaButtonsScale(this@LockScreenActivity) / 100f
            scaleX = buttonScale
            scaleY = buttonScale
        }
        mediaPanel.addView(controls)
        if (Prefs.showMedia(this)) content.addView(mediaPanel,
            LinearLayout.LayoutParams(-1, dp(if (mediaCompact) 74 else if (mediaLarge) 108 else 94)).apply { bottomMargin = dp(12) })
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val floatingBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = Appearance.floatingBar(this@LockScreenActivity)
            elevation = dp(16).toFloat()
            addView(shortcutButton("left"), LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(TextView(this@LockScreenActivity).apply {
                text = if (Prefs.unlockMethod(this@LockScreenActivity) == "pattern") "PATTERN" else "PIN"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Unlock D2"
                setOnClickListener { authenticate() }
                setOnTouchListener(unlockSwipeListener())
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
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
            val horizontalPad = when (density) { "compact" -> 13; "large" -> 18; else -> 16 }
            val verticalPad = when (density) { "compact" -> 7; "large" -> 14; else -> 11 }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(horizontalPad), dp(verticalPad), dp(horizontalPad), dp(verticalPad))
                background = Appearance.glass(this@LockScreenActivity, Prefs.notificationRadius(this@LockScreenActivity).toFloat(), Prefs.notificationGlass(this@LockScreenActivity), true)
                addView(notificationLabel(item.app, 12f))
                if (privacy == 4 || (privacy == 3 && item.visibility == Notification.VISIBILITY_PUBLIC)) {
                    addView(notificationLabel(item.title.ifBlank { item.text }, 16f))
                    if (item.title.isNotBlank() && item.text.isNotBlank()) addView(notificationLabel(item.text, 13f))
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
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }; return true }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dx = event.x - downX
                    if (kotlin.math.abs(dx) > dp(2)) {
                        card.translationX = dx
                        card.alpha = (1f - kotlin.math.abs(dx) / (card.width.coerceAtLeast(1) * .65f)).coerceIn(.22f, 1f)
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
                    if (kotlin.math.abs(dx) > card.width * .08f || (kotlin.math.abs(dx) > dp(8) && kotlin.math.abs(velocityX) > dp(280))) {
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

    private fun authenticate(afterUnlock: (() -> Unit)? = null) {
        if (preview) { finish(); return }
        if (pinDialog?.isShowing == true) return
        val unlocked = {
            unlocking = true
            playUnlockHaptic()
            RootKiosk.unlock(this) {
                app.d2lock.bridge.IslandBridge.setLocked(this, false)
                afterUnlock?.invoke()
                finish()
            }
        }
        if (Prefs.unlockMethod(this) == "pattern" && PatternStore(this).configured()) {
            pinDialog = PatternUi.show(this, success = unlocked, usePin = {
                pinDialog = PinUi.show(this, success = unlocked)
            })
        } else {
            pinDialog = PinUi.show(this, success = unlocked)
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
        "Camera" -> roundButton("📷") {
            if (preview) openCamera() else authenticate { openCamera() }
        }.apply { contentDescription = "Camera, D2 authentication required" }
        "Flashlight" -> roundButton("🔦") { toggleTorch() }.apply { contentDescription = "Flashlight" }
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

    private fun glassPanel(radius: Float = 28f) =
        Appearance.glass(this, radius, 36, true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
