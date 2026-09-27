package app.d2lock.lockscreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.d2lock.MainActivity
import app.d2lock.Prefs
import app.d2lock.R
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore

class LockScreenService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && Prefs.enabled(context) && PinStore(context).configured()) {
                val callActive = app.d2lock.notifications.CallNotificationStore.items.isNotEmpty()
                Log.i(TAG, "GUARDIAN_SCREEN_ON callActive=$callActive")
                GuardianWatchdog.reassert(context, "screen_on", callActive)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GUARDIAN_SERVICE_CREATED")
        val channel = NotificationChannel(CHANNEL, "Lock-screen companion", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("Kiosk D2 Guardian is ready")
            .setContentText("Tap to configure or preview")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(ID, notification)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val postBoot = intent?.getBooleanExtra(EXTRA_POST_BOOT, false) == true
        Log.i(TAG, "GUARDIAN_SERVICE_STARTED action=$action postBoot=$postBoot")
        if (action == ACTION_LOCK_SURFACE_HEALTHY) {
            markLockSurfaceHealthy()
            return START_STICKY
        }
        if (action == ACTION_XPOSED_FRAMEWORK_EVENT) {
            val event = intent?.getStringExtra(EXTRA_FRAMEWORK_EVENT) ?: "UNKNOWN"
            val source = intent?.getStringExtra(EXTRA_KEYGUARD_SOURCE) ?: "system_server"
            Log.i(TAG, "GUARDIAN_FRAMEWORK_HANDOFF event=$event source=$source")
            if (event == "WAKE" || event == "KEYGUARD" || event == "TASK_KEYGUARD") {
                val callActive = app.d2lock.notifications.CallNotificationStore.items.isNotEmpty()
                if (!callActive && Prefs.enabled(this) && PinStore(this).configured()) {
                    GuardianWatchdog.reassert(this, "framework_${event.lowercase()}", callActive)
                } else {
                    Log.i(TAG, "GUARDIAN_FRAMEWORK_REASSERT_SKIPPED event=$event callActive=$callActive")
                }
            }
            return START_STICKY
        }
        if (action == ACTION_XPOSED_KEYGUARD_STATE) {
            val state = intent?.getStringExtra(EXTRA_KEYGUARD_STATE) ?: "UNKNOWN"
            val source = intent?.getStringExtra(EXTRA_KEYGUARD_SOURCE) ?: "unknown"
            Log.i(TAG, "GUARDIAN_KEYGUARD_HANDOFF state=$state source=$source")
            when (state) {
                "SHOWING", "GOING_AWAY" -> {
                    handler.removeCallbacksAndMessages(SURFACE_RECOVERY_TOKEN)
                    keyguardHandoffActive = true
                    Log.i(TAG, "GUARDIAN_BOOT_WAIT_KEYGUARD state=$state")
                }
                "CLEAR" -> {
                    handler.removeCallbacksAndMessages(SURFACE_RECOVERY_TOKEN)
                    keyguardHandoffActive = false
                    handler.postAtTime({
                        if (Prefs.enabled(this) && PinStore(this).configured() &&
                            app.d2lock.notifications.CallNotificationStore.items.isEmpty()) {
                            Log.i(TAG, "GUARDIAN_KEYGUARD_CLEAR launch=single")
                            val launched = Prefs.rootMode(this) && RootManager.launchCompanion()
                            Log.i(TAG, "GUARDIAN_D2_HANDOFF_LAUNCH path=${if (launched) "root" else "activity"}")
                            if (!launched) runCatching {
                                startActivity(Intent(this, LockScreenActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            }
                        }
                    }, SURFACE_RECOVERY_TOKEN, SystemClock.uptimeMillis() + KEYGUARD_CLEAR_SETTLE_MS)
                }
            }
            return START_STICKY
        }
        if (action == ACTION_LOCK_SURFACE_LOST) {
            val reason = intent?.getStringExtra(EXTRA_REASON) ?: "unknown"
            Log.w(TAG, "GUARDIAN_SURFACE_LOST reason=$reason")
            handler.removeCallbacksAndMessages(SURFACE_RECOVERY_TOKEN)
            if (keyguardHandoffActive) {
                Log.i(TAG, "GUARDIAN_SURFACE_RECOVERY_DEFERRED reason=$reason keyguardHandoff=true")
                return START_STICKY
            }
            handler.postAtTime({
                if (Prefs.enabled(this) && PinStore(this).configured() && app.d2lock.notifications.CallNotificationStore.items.isEmpty()) {
                    val launched = Prefs.rootMode(this) && RootManager.launchCompanion()
                    Log.i(TAG, "GUARDIAN_SURFACE_RECOVERY reason=$reason path=${if (launched) "root" else "activity"}")
                    if (!launched) runCatching {
                        startActivity(Intent(this, LockScreenActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                }
            }, SURFACE_RECOVERY_TOKEN, SystemClock.uptimeMillis() + 120L)
            return START_STICKY
        }
        if (!Prefs.enabled(this) || !PinStore(this).configured()) {
            Log.i(TAG, "GUARDIAN_SERVICE_STOPPED_NOT_ARMED")
            stopSelf()
            return START_NOT_STICKY
        }
        if (postBoot && app.d2lock.notifications.CallNotificationStore.items.isEmpty() && postBootLaunchAllowed()) {
            val root = Prefs.rootMode(this)
            val launched = root && RootManager.launchCompanion()
            if (launched) {
                Log.i(TAG, "GUARDIAN_LOCKSCREEN_REQUESTED path=root")
            } else {
                runCatching {
                    startActivity(Intent(this, LockScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onSuccess { Log.i(TAG, "GUARDIAN_LOCKSCREEN_REQUESTED path=activity") }
                 .onFailure { Log.w(TAG, "GUARDIAN_LOCKSCREEN_REQUEST_FAILED", it) }
            }
        }
        return START_STICKY
    }

    private fun markLockSurfaceHealthy() {
        getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_ATTEMPTS, 0).putLong(KEY_WINDOW_START, 0L)
            .putLong(KEY_LAST_HEALTHY, System.currentTimeMillis()).apply()
        Log.i(TAG, "GUARDIAN_HEALTHY")
    }

    private fun postBootLaunchAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
        var start = prefs.getLong(KEY_WINDOW_START, 0L)
        var attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        if (start == 0L || now - start > BOOT_WINDOW_MS) { start = now; attempts = 0 }
        if (attempts >= MAX_BOOT_ATTEMPTS) {
            Log.w(TAG, "GUARDIAN_BOOT_SUPPRESSED attempts=$attempts")
            return false
        }
        attempts++
        prefs.edit().putLong(KEY_WINDOW_START, start).putInt(KEY_ATTEMPTS, attempts).apply()
        Log.i(TAG, "GUARDIAN_BOOT_ATTEMPT n=$attempts")
        return true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SamsungLockD2"
        private const val CHANNEL = "lock_companion"
        private const val ID = 3701
        private const val RECOVERY_PREFS = "d2_guardian_recovery"
        private const val KEY_ATTEMPTS = "boot_attempts"
        private const val KEY_WINDOW_START = "boot_window_start"
        private const val KEY_LAST_HEALTHY = "last_healthy"
        private const val BOOT_WINDOW_MS = 120_000L
        private const val MAX_BOOT_ATTEMPTS = 2
        private const val EXTRA_POST_BOOT = "d2_post_boot"
        private const val ACTION_LOCK_SURFACE_HEALTHY = "app.d2lock.action.LOCK_SURFACE_HEALTHY"
        private const val ACTION_LOCK_SURFACE_LOST = "app.d2lock.action.LOCK_SURFACE_LOST"
        private const val ACTION_XPOSED_KEYGUARD_STATE = "app.d2lock.action.XPOSED_KEYGUARD_STATE_INTERNAL"
        private const val ACTION_XPOSED_FRAMEWORK_EVENT = "app.d2lock.action.XPOSED_FRAMEWORK_EVENT_INTERNAL"
        private const val EXTRA_FRAMEWORK_EVENT = "framework_event"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_KEYGUARD_STATE = "keyguard_state"
        private const val EXTRA_KEYGUARD_SOURCE = "keyguard_source"
        private const val KEYGUARD_CLEAR_SETTLE_MS = 180L
        @Volatile private var keyguardHandoffActive = false
        private val SURFACE_RECOVERY_TOKEN = Any()
        fun start(context: Context, postBoot: Boolean = false) = context.startForegroundService(Intent(context, LockScreenService::class.java).putExtra(EXTRA_POST_BOOT, postBoot))
        fun markHealthy(context: Context) = context.startService(Intent(context, LockScreenService::class.java).setAction(ACTION_LOCK_SURFACE_HEALTHY))
        fun surfaceLost(context: Context, reason: String) = context.startService(Intent(context, LockScreenService::class.java).setAction(ACTION_LOCK_SURFACE_LOST).putExtra(EXTRA_REASON, reason))
        fun frameworkSignal(context: Context, event: String, source: String) =
            context.startService(Intent(context, LockScreenService::class.java)
                .setAction(ACTION_XPOSED_FRAMEWORK_EVENT)
                .putExtra(EXTRA_FRAMEWORK_EVENT, event)
                .putExtra(EXTRA_KEYGUARD_SOURCE, source))
        fun keyguardSignal(context: Context, state: String, source: String) =
            context.startService(Intent(context, LockScreenService::class.java)
                .setAction(ACTION_XPOSED_KEYGUARD_STATE)
                .putExtra(EXTRA_KEYGUARD_STATE, state)
                .putExtra(EXTRA_KEYGUARD_SOURCE, source))
        fun stop(context: Context) = context.stopService(Intent(context, LockScreenService::class.java))
    }
}
