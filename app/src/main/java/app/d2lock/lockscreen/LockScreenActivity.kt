package app.d2lock.lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import app.d2lock.widget.DoubleTap
import app.d2lock.media.MediaControllerBridge
import app.d2lock.notifications.NotificationStore
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

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
            battery.text = if (charging) "⚡ $level%" else "$level%"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PinUi.protect(this)
        preview = intent.getBooleanExtra("preview", false)
        if (!preview && !PinStore(this).configured()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
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
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        NotificationStore.onChanged = { runOnUiThread(::renderNotifications) }
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
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        super.onDestroy()
    }

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff202637.toInt(), 0xff0b101b.toInt(), 0xff070910.toInt()))
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
        clock = label("12:00", 82f, Color.WHITE).apply { letterSpacing = -.05f; gravity = Gravity.CENTER }
        date = label("", 18f, 0xffeeeeF4.toInt()).apply { gravity = Gravity.CENTER }
        val topInfo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            weather = label("Loading weather…", 15f, Color.WHITE)
            battery = label("—%", 15f, Color.WHITE)
            addView(weather, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(battery, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        content.addView(clock)
        content.addView(date)
        kioskStatus = label(if (preview) "Preview • unlocked" else if (Prefs.kiosk(this)) "Starting root kiosk…" else "App-only mode • Home/Recents can exit", 12f, Color.WHITE).apply {
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
        content.addView(label("Weather: Open-Meteo.com · CC BY 4.0", 12f, Color.WHITE).apply {
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

        notifications = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(notifications, LinearLayout.LayoutParams(-1, 0, 1f))

        val mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = glassPanel(30f)
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
            playPause = mediaButton("▶") { media.toggle() }
            addView(playPause)
            addView(mediaButton("›") { media.next() })
        }
        mediaPanel.addView(controls)
        if (Prefs.showMedia(this)) content.addView(mediaPanel,
            LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(12) })
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val floatingBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = glassPanel(38f)
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
        }
        frame.addView(floatingBar, FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM).apply {
            setMargins(dp(20), 0, dp(20), dp(30))
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
        notifications.removeAllViews()
        val privacy = if (preview) 3 else Prefs.notificationPrivacy(this)
        if (privacy == 0) return
        if (NotificationStore.items.isEmpty()) {
            notifications.addView(label("No notifications", 14f, 0xffddddE5.toInt()).apply { gravity = Gravity.CENTER })
            return
        }
        if (privacy == 1) {
            notifications.addView(label("${NotificationStore.items.size} notifications", 15f, Color.WHITE).apply {
                gravity = Gravity.CENTER
            })
            return
        }
        NotificationStore.items.take(4).forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(11), dp(16), dp(11))
                background = glassPanel()
                addView(label(item.app, 12f, 0xffd8d5e5.toInt()))
                if (privacy == 3 && (preview || item.public)) {
                    addView(label(item.title.ifBlank { item.text }, 16f, Color.WHITE))
                    if (item.title.isNotBlank() && item.text.isNotBlank()) addView(label(item.text, 13f, 0xffe6e3ed.toInt()))
                }
            }
            notifications.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun authenticate(afterUnlock: (() -> Unit)? = null) {
        if (preview) { finish(); return }
        if (pinDialog?.isShowing == true) return
        pinDialog = PinUi.show(this, success = {
            unlocking = true
            RootKiosk.unlock(this) {
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
        text = value; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
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
        else -> View(this)
    }

    private fun glassPanel(radius: Float = 28f) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius.toInt()).toFloat()
        setColor(0xa3353b4c.toInt())
        setStroke(dp(1), 0x55ffffff)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
