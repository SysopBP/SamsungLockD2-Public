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
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi
import app.d2lock.security.PatternStore
import app.d2lock.security.PatternUi
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
    private lateinit var kioskStatus: TextView
    private var pinDialog: AlertDialog? = null
    private var wallpaperActive = false
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private val wallpaperAnimations = mutableListOf<ObjectAnimator>()

    private val ticker = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            mediaTitle.text = media.title().ifBlank { "Media" }
            mediaArtist.text = media.artist().ifBlank { "Play music to show it here" }
            playPause.text = if (media.isPlaying()) "Ⅱ" else "▶"
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
            battery.text = if (charging) "⚡ $level%" else "$level%"
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
        if (!preview && Prefs.quickSettingsGuard(this)) handler.post(quickSettingsGuard)
        if (!preview && !unlocking && Prefs.kiosk(this)) {
            RootKiosk.attach(this) { kioskStatus.text = it }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode && !preview && Prefs.kiosk(this)) {
            // A kiosk lock screen must never remain usable as one pane of multi-window.
            // Reassert the task/window immediately; RootKiosk continues verifying LOCK_TASK_MODE_LOCKED.
            window.decorView.post {
                if (!isDestroyed && !isFinishing) {
                    runCatching { startLockTask() }
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            }
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
        handler.removeCallbacks(quickSettingsGuard)
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
            val dim = Prefs.wallpaperDim(this)
            if (dim > 0) frame.addView(View(this).apply {
                setBackgroundColor(Color.argb((255f * dim / 100f).toInt(), 0, 0, 0))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(72), dp(20), dp(125))
        }
        clock = label("12:00", 82f, Appearance.text(this, true)).apply { letterSpacing = -.05f; gravity = Gravity.CENTER }
        date = label("", 18f, Appearance.secondary(this, true)).apply { gravity = Gravity.CENTER }
        val topInfo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            weather = label("Loading weather…", 15f, Appearance.text(this@LockScreenActivity, true)).apply { gravity = Gravity.CENTER }
            battery = label("—%", 15f, Appearance.text(this@LockScreenActivity, true)).apply { gravity = Gravity.CENTER }
            // One continuous transparent weather/battery pill, matching the AMOLED glass design.
            background = Appearance.glass(this@LockScreenActivity, 30f, 32, true)
            addView(weather, LinearLayout.LayoutParams(0, dp(42), 1f))
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
        content.addView(topInfo, LinearLayout.LayoutParams(-1, dp(54)))
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

        val mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = Appearance.glass(this@LockScreenActivity, 34f, 42, true)
            elevation = dp(8).toFloat()
        }
        mediaPanel.addView(label("♫", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0xff41475d.toInt())
            }
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
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
        }
        mediaPanel.addView(mediaDetails, LinearLayout.LayoutParams(0, -2, 1f))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaButton("‹") { media.previous() })
            playPause = mediaButton("▶", true) { media.toggle() }
            addView(playPause)
            addView(mediaButton("›") { media.next() })
        }
        mediaPanel.addView(controls)
        if (Prefs.showMedia(this)) content.addView(mediaPanel,
            LinearLayout.LayoutParams(-1, dp(94)).apply { bottomMargin = dp(12) })
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
                setPadding(dp(16), dp(11), dp(16), dp(11))
                background = notificationPanel(item.packageName)
                addView(notificationLabel(item.app, 12f))
                if (privacy == 4 || (privacy == 3 && item.visibility == Notification.VISIBILITY_PUBLIC)) {
                    addView(notificationLabel(item.title.ifBlank { item.text }, 16f))
                    if (item.title.isNotBlank() && item.text.isNotBlank()) addView(notificationLabel(item.text, 13f))
                }
            }
            if (item.contentIntent != null) card.setOnClickListener { openNotification(item) }
            card.setOnTouchListener(notificationDismissListener(item, card))
            notifications.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
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
                    if (dx > dp(4)) {
                        card.translationX = dx
                        card.alpha = (1f - dx / (card.width.coerceAtLeast(1) * .8f)).coerceIn(.28f, 1f)
                    } else if (dx < 0f) card.translationX = dx * .12f
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocityX = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx > card.width * .20f || (dx > dp(18) && velocityX > dp(650))) {
                        card.animate().translationX(card.width.toFloat()).alpha(0f).setDuration(150).withEndAction {
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
        if (primary) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x32ffffff); setStroke(dp(1), 0x66ffffff) }
        layoutParams = LinearLayout.LayoutParams(if (primary) dp(58) else dp(35), if (primary) dp(58) else dp(48))
        setOnClickListener { click() }
    }
    private fun shortcutButton(side: String): View = when (Prefs.shortcut(this, side)) {
        "Camera" -> roundButton("📷") {
            if (preview) openCamera() else authenticate { openCamera() }
        }.apply { contentDescription = "Camera, D2 authentication required" }
        "Flashlight" -> roundButton("🔦") { toggleTorch() }.apply { contentDescription = "Flashlight" }
        else -> View(this)
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
