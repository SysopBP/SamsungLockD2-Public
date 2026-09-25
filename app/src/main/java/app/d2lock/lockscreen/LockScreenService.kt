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
import android.util.Log
import app.d2lock.MainActivity
import app.d2lock.Prefs
import app.d2lock.R
import app.d2lock.root.RootManager
import app.d2lock.security.PinStore

class LockScreenService : Service() {
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
            .setContentTitle("Samsung Lock D2 is ready")
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
        if (!Prefs.enabled(this) || !PinStore(this).configured()) {
            Log.i(TAG, "GUARDIAN_SERVICE_STOPPED_NOT_ARMED")
            stopSelf()
            return START_NOT_STICKY
        }
        if (postBoot && app.d2lock.notifications.CallNotificationStore.items.isEmpty() && postBootLaunchAllowed()) {
            GuardianWatchdog.reassert(this, "post_boot", false)
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
        fun start(context: Context, postBoot: Boolean = false) = context.startForegroundService(Intent(context, LockScreenService::class.java).putExtra(EXTRA_POST_BOOT, postBoot))
        fun markHealthy(context: Context) = context.startService(Intent(context, LockScreenService::class.java).setAction(ACTION_LOCK_SURFACE_HEALTHY))
        fun stop(context: Context) = context.stopService(Intent(context, LockScreenService::class.java))
    }
}
