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

                // Do not blanket-skip wake recovery during a call. That left Launcher/Home
                // exposed after power-button or double-tap sleep. Root companion gets first
                // chance because it can make the foreground decision without Android's
                // background-activity launch restrictions.
                if (Prefs.rootMode(context) && RootManager.launchCompanion()) {
                    Log.i(TAG, "GUARDIAN_WAKE_REASSERT_REQUESTED path=root callActive=$callActive")
                    return
                }

                // Non-root fallback mirrors the known-good widget launch path. The lock
                // activity itself remains responsible for trusted call-UI handling.
                runCatching {
                    startActivity(Intent(context, LockScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    Log.i(TAG, "GUARDIAN_WAKE_REASSERT_REQUESTED path=activity callActive=$callActive")
                }.onFailure {
                    Log.w(TAG, "GUARDIAN_WAKE_REASSERT_FAILED callActive=$callActive", it)
                }
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
        Log.i(TAG, "GUARDIAN_SERVICE_STARTED")
        if (!Prefs.enabled(this) || !PinStore(this).configured()) {
            Log.i(TAG, "GUARDIAN_SERVICE_STOPPED_NOT_ARMED")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SamsungLockD2"
        private const val CHANNEL = "lock_companion"
        private const val ID = 3701
        fun start(context: Context) = context.startForegroundService(Intent(context, LockScreenService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, LockScreenService::class.java))
    }
}
