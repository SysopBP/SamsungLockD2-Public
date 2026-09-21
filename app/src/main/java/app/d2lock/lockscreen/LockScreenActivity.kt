package app.d2lock.lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.AlarmManager
import android.media.AudioManager
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
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import app.d2lock.Prefs
import app.d2lock.MainActivity
import app.d2lock.root.RootKiosk
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi
import app.d2lock.widget.DoubleTap
import app.d2lock.media.MediaControllerBridge
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
    private lateinit var nextAlarm: TextView
    private lateinit var notifications: LinearLayout
    private lateinit var liveBanner: TextView
    private var bannerKey: String? = null
    private val hideBanner = Runnable {
        if (::liveBanner.isInitialized) liveBanner.visibility = View.GONE
        bannerKey = null
    }
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArt: ImageView
    private lateinit var mediaArtist: TextView
    private lateinit var playPause: TextView
    private lateinit var mediaProgress: SeekBar
    private lateinit var mediaTime: TextView
    private lateinit var media: MediaControllerBridge
    private var torchOn = false
    private var preview = false
    private var unlocking = false
    private lateinit var kioskStatus: TextView
    private var pinDialog: AlertDialog? = null
    private var wallpaperActive = false
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private val wallpaperAnimations = mutableListOf<ObjectAnimator>()

    private val ticker = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat(if (Prefs.clockStyle(this@LockScreenActivity) == 2) "HH:mm" else "h:mm", Locale.getDefault()).format(Date())
            if (Prefs.oledShift(this@LockScreenActivity)) {
                val slot = (System.currentTimeMillis() / 60000L % 5L).toInt()
                clock.translationX = dp(slot - 2).toFloat()
                clock.translationY = dp((slot % 3) - 1).toFloat()
                date.translationX = clock.translationX
            } else {
                clock.translationX = 0f; clock.translationY = 0f; date.translationX = 0f
            }
            date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            mediaTitle.text = media.title().ifBlank { "Media" }
            mediaArtist.text = media.artist().ifBlank { "Play music to show it here" }
            playPause.text = if (media.isPlaying()) "Ⅱ" else "▶"
            media.albumArt()?.let { mediaArt.setImageBitmap(it) } ?: mediaArt.setImageDrawable(null)
            val duration = media.durationMs()
            val position = media.positionMs().coerceAtMost(duration.coerceAtLeast(0L))
            if (::mediaProgress.isInitialized && !mediaProgress.isPressed) {
                mediaProgress.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                mediaProgress.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            if (::mediaTime.isInitialized) mediaTime.text = formatMediaTime(position, duration)
            handler.postDelayed(this, 1000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val source = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Battery"
            }
            val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val temp = if (Prefs.celsius(this@LockScreenActivity)) String.format(Locale.getDefault(), "%.1f°C", tempC)
                else String.format(Locale.getDefault(), "%.1f°F", tempC * 9f / 5f + 32f)
            battery.text = when {
                charging && Prefs.detailedBattery(this@LockScreenActivity) -> "$source • $level% • $temp"
                charging -> "Charging • $level%"
                Prefs.detailedBattery(this@LockScreenActivity) -> "$level% • $temp"
                else -> "$level%"
            }
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
        NotificationStore.onPosted = { item -> runOnUiThread { showLiveNotification(item) } }
        renderNotifications()
        WeatherRepository.load(this) { value -> runOnUiThread {
            weather.text = value?.let { "${it.temperature}°${if (Prefs.celsius(this)) "C" else "F"}  ${it.label}" } ?: "Weather unavailable"
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!::clock.isInitialized) return
        wallpaperActive = true
        wallpaperAnimations.forEach { it.resume() }
        renderNotifications()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            batteryReceiver.onReceive(this, it)
        }
        handler.post(ticker)
        if (!preview && !unlocking && Prefs.kiosk(this)) {
            RootKiosk.attach(this) { kioskStatus.text = it }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        recreate()
    }

    override fun onPause() {
        wallpaperActive = false
        wallpaperAnimations.forEach { it.pause() }
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onPause()
    }

    override fun onStop() {
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
        RootKiosk.detach(this)
        wallpaperAnimations.forEach { it.cancel() }
        wallpaperAnimations.clear()
        wallpaperExecutor.shutdownNow()
        if (NotificationStore.onChanged != null) NotificationStore.onChanged = null
        NotificationStore.onPosted = null
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
                alpha = .72f
                val wallpaperView = this
                wallpaperExecutor.execute {
                    val bitmap = runCatching { WallpaperDecoder.decode(applicationContext, Uri.parse(saved)) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed || isFinishing) {
                            bitmap?.recycle()
                        } else if (bitmap != null) {
                            wallpaperView.setImageBitmap(bitmap)
                            listOf(View.SCALE_X, View.SCALE_Y).forEach { property ->
                                wallpaperAnimations += ObjectAnimator.ofFloat(wallpaperView, property, 1f, 1.06f).apply {
                                    duration = 14000
                                    repeatCount = ValueAnimator.INFINITE
                                    repeatMode = ValueAnimator.REVERSE
                                    start()
                                    if (!wallpaperActive) pause()
                                }
                            }
                        } else {
                            Toast.makeText(this@LockScreenActivity, "Wallpaper could not be opened. Choose another image in settings.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(72), dp(20), dp(125))
        }
        val clockSize = when (Prefs.clockStyle(this)) { 1 -> 64f; 2 -> 56f; else -> 84f }
        clock = label("12:00", clockSize, Appearance.text(this, true)).apply {
            letterSpacing = if (Prefs.clockStyle(this@LockScreenActivity) == 2) -.02f else -.055f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        date = label("", 17f, Appearance.secondary(this, true)).apply { gravity = Gravity.CENTER; includeFontPadding = false; setPadding(0, dp(2), 0, dp(5)) }
        val topInfo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = glassPanel(24f)
            weather = label("Loading weather…", 14f, Appearance.text(this@LockScreenActivity, true)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            battery = label("—%", 14f, Appearance.text(this@LockScreenActivity, true)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            if (Prefs.showWeatherWidget(this@LockScreenActivity)) {
                addView(weather, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
            if (Prefs.showWeatherWidget(this@LockScreenActivity) && Prefs.showBatteryWidget(this@LockScreenActivity)) {
                addView(View(this@LockScreenActivity).apply {
                    background = GradientDrawable().apply { setColor(0x33ffffff) }
                }, LinearLayout.LayoutParams(dp(1), dp(22)))
            }
            if (Prefs.showBatteryWidget(this@LockScreenActivity)) {
                addView(battery, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
        }
        content.addView(clock)
        content.addView(date)
        kioskStatus = label(if (preview) "Preview • unlocked" else if (Prefs.kiosk(this)) "Starting root kiosk…" else "App-only mode • Home/Recents can exit", 12f, Appearance.text(this, true)).apply {
            gravity = Gravity.CENTER
        }
        content.addView(kioskStatus)
        val alarmClock = getSystemService(AlarmManager::class.java).nextAlarmClock
        nextAlarm = label(alarmClock?.let { "Next alarm • " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(it.triggerTime)) } ?: "No alarm set", 12f, Appearance.secondary(this, true)).apply { gravity = Gravity.CENTER }
        content.addView(nextAlarm)
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
        if (Prefs.showWeatherWidget(this) || Prefs.showBatteryWidget(this)) {
            content.addView(topInfo, LinearLayout.LayoutParams(-1, dp(50)).apply {
                leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(6); bottomMargin = dp(4)
            })
        }
        content.addView(label("Weather: Open-Meteo.com · CC BY 4.0", 12f, Appearance.text(this, true)).apply {
            gravity = Gravity.CENTER
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                val openCredit = {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/en/licence")))
                    }.onFailure { Toast.makeText(this@LockScreenActivity, "Browser unavailable", Toast.LENGTH_SHORT).show() }
                    Unit
                }
                if (preview) openCredit() else authenticate(openCredit)
            }
        })

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

        val mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = glassPanel(32f)
            elevation = dp(8).toFloat()
        }
        mediaArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Album artwork"
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xff41475d.toInt())
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
        }
        mediaPanel.addView(mediaArt, LinearLayout.LayoutParams(dp(56), dp(56)))
        val mediaDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            mediaTitle = label("Media", 16f, Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            mediaArtist = label("Play music to show it here", 12f, 0xffd1d4df.toInt()).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(mediaTitle)
            addView(mediaArtist)
            mediaProgress = SeekBar(this@LockScreenActivity).apply {
                maxHeight = dp(3)
                setPadding(0, dp(2), 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && ::mediaTime.isInitialized) mediaTime.text = formatMediaTime(progress.toLong(), media.durationMs())
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBar?.let { media.seekTo(it.progress.toLong()) } }
                })
            }
            addView(mediaProgress, LinearLayout.LayoutParams(-1, dp(16)))
            mediaTime = label("0:00", 9f, 0xffbfc3cf.toInt()).apply { gravity = Gravity.END; includeFontPadding = false }
            addView(mediaTime)
        }
        mediaPanel.addView(mediaDetails, LinearLayout.LayoutParams(0, -2, 1f))
        mediaPanel.contentDescription = "Now Playing media controls"
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaButton("‹") { media.previous() })
            playPause = mediaButton("▶") { media.toggle() }
            addView(playPause)
            addView(mediaButton("›") { media.next() })
        }
        mediaPanel.addView(controls)
        if (Prefs.showMedia(this)) content.addView(mediaPanel,
            LinearLayout.LayoutParams(-1, dp(92)).apply { bottomMargin = dp(8) })
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val floatingBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = Appearance.floatingBar(this@LockScreenActivity)
            elevation = dp(16).toFloat()
            addView(shortcutButton("left"), LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(TextView(this@LockScreenActivity).apply {
                text = "PIN"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Enter D2 PIN"
                setOnClickListener { authenticate() }
                setOnTouchListener(unlockSwipeListener())
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(shortcutButton("right"), LinearLayout.LayoutParams(dp(58), dp(58)))
            setOnLongClickListener {
                if (preview) showRecoveryDrawer() else if (pinDialog?.isShowing != true) {
                    pinDialog = PinUi.show(this@LockScreenActivity, success = {
                        pinDialog = null
                        showRecoveryDrawer()
                    }, cancel = { pinDialog = null })
                }
                true
            }
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

    private fun formatMediaTime(position: Long, duration: Long): String {
        fun format(ms: Long): String {
            val total = (ms.coerceAtLeast(0L) / 1000)
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }
        return if (duration > 0) "${format(position)} / ${format(duration)}" else format(position)
    }

    private fun showRecoveryDrawer() {
        if (!RootManager.isAvailable()) {
            Toast.makeText(this, "Recovery Drawer requires KernelSU/root.", Toast.LENGTH_LONG).show()
            return
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(14))
            addView(label("ROOT • KIOSK • ADB", 13f, Appearance.secondary(this@LockScreenActivity, true)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10))
            })
            addView(label(RootManager.usbDiagnostics(), 13f, Appearance.text(this@LockScreenActivity, true)).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = glassPanel(20f)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        fun addAction(title: String, action: () -> Boolean) {
            body.addView(actionButton(title) {
                if (action()) Toast.makeText(this, title + " requested", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, title + " failed", Toast.LENGTH_LONG).show()
            })
        }
        val adb = RootManager.adbUsbEnabled()
        addAction(if (adb) "Block ADB / USB" else "Allow ADB / USB") { RootManager.setAdbUsbEnabled(!adb) }
        addAction("Reset USB / restart ADB") { RootManager.resetAdbUsb() }
        addAction("Restart D2 wake service") {
            LockScreenService.stop(this)
            if (Prefs.enabled(this)) LockScreenService.start(this)
            true
        }
        addAction("Restart System UI") { RootManager.restartSystemUi() }
        body.addView(actionButton("Soft reboot Android") {
            AlertDialog.Builder(this)
                .setTitle("Soft reboot Android?")
                .setMessage("Android userspace will restart and unsaved work can be lost.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Soft reboot") { _, _ -> RootManager.softReboot() }
                .show()
        })
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle("D2 Recovery Drawer")
            .setMessage("Root-only recovery controls. Long-press the bottom D2 bar to return here.")
            .setView(body)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Appearance.surface(this@LockScreenActivity))
                setStroke(dp(1), Appearance.secondary(this@LockScreenActivity))
            })
        }
        dialog.show()
    }

    private fun unlockSwipeListener() = object : View.OnTouchListener {
        private var startY = 0f
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = event.y; return true }
                android.view.MotionEvent.ACTION_UP -> {
                    if (startY - event.y > dp(36) || kotlin.math.abs(startY - event.y) < dp(12)) authenticate()
                    return true
                }
            }
            return false
        }
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
            notifications.addView(label("No visible notifications", 14f, Appearance.secondary(this, true)).apply { gravity = Gravity.CENTER })
            return
        }
        if (privacy == 1) {
            notifications.addView(label("${visibleItems.size} notifications", 15f, Appearance.text(this, true)).apply {
                gravity = Gravity.CENTER
            })
            return
        }
        visibleItems.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(13), dp(18), dp(13))
                background = notificationPanel(item.packageName)
                addView(notificationLabel(item.app, 12f))
                if (privacy == 4 || (privacy == 3 && item.visibility == Notification.VISIBILITY_PUBLIC)) {
                    addView(notificationLabel(item.title.ifBlank { item.text }, 16f))
                    if (item.title.isNotBlank() && item.text.isNotBlank()) addView(notificationLabel(item.text, 13f))
                }
            }
            if (item.contentIntent != null) card.setOnClickListener { openNotification(item) }
            card.elevation = dp(5).toFloat()
            card.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            v.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - downX
                            if (kotlin.math.abs(dx) > dp(10)) {
                                v.translationX = dx * .35f
                                v.alpha = (1f - kotlin.math.abs(dx) / (v.width.coerceAtLeast(1) * 1.6f)).coerceAtLeast(.55f)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            if (kotlin.math.abs(dx) > v.width * .32f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat())
                                    .alpha(0f).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
                                true
                            } else {
                                v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                                if (kotlin.math.abs(dx) < dp(12) && kotlin.math.abs(dy) < dp(12)) v.performClick()
                                true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
                        }
                    }
                    return true
                }
            })
            notifications.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
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
        pinDialog = PinUi.show(this, success = {
            unlocking = true
            RootKiosk.unlock(this) {
                app.d2lock.bridge.IslandBridge.setLocked(this, false)
                afterUnlock?.invoke()
                finish()
            }
        })
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

    private fun mediaButton(value: String, click: () -> Unit) = TextView(this).apply {
        text = value; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(35), dp(48))
        setOnClickListener { click() }
    }
    private fun shortcutButton(side: String): View = when (Prefs.shortcut(this, side)) {
        "Camera" -> roundButton("📷") {
            if (preview) openCamera() else authenticate { openCamera() }
        }.apply { contentDescription = "Camera, D2 PIN required" }
        "Flashlight" -> roundButton("🔦") { toggleTorch() }.apply { contentDescription = "Flashlight" }
        "Calculator" -> roundButton("⌗") {
            val open = {
                val launch = packageManager.getLaunchIntentForPackage("com.sec.android.app.popupcalculator")
                    ?: packageManager.getLaunchIntentForPackage("com.google.android.calculator")
                if (launch != null) startActivity(launch) else Toast.makeText(this, "Calculator unavailable", Toast.LENGTH_SHORT).show()
            }
            if (preview) open() else authenticate(open)
        }.apply { contentDescription = "Calculator, D2 PIN required" }
        "Silent / Vibrate" -> roundButton("♬") {
            val audio = getSystemService(AudioManager::class.java)
            audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            Toast.makeText(this, if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) "Vibrate" else "Sound", Toast.LENGTH_SHORT).show()
        }.apply { contentDescription = "Toggle sound and vibrate" }
        "Do Not Disturb" -> roundButton("☾") {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else {
                val enabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (enabled) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }.apply { contentDescription = "Toggle Do Not Disturb" }
        else -> View(this)
    }

    private fun notificationLabel(value: String, size: Float) = label(value, size, Appearance.text(this, true)).apply {
        setShadowLayer(if (Appearance.dark(this@LockScreenActivity, true)) dp(2).toFloat() else 0f, 0f, dp(1).toFloat(), 0xcc000000.toInt())
    }

    private fun notificationPanel(app: String?, banner: Boolean = false, call: Boolean = false) =
        Appearance.panel(this, app, banner, call)

    private fun glassPanel(radius: Float = 28f) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius.toInt()).toFloat()
        setColor(0xdd000000.toInt() or (Appearance.blend(Color.BLACK, Appearance.accent(this@LockScreenActivity), .3f) and 0xffffff))
        setStroke(dp(1), 0x55ffffff)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
