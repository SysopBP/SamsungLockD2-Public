package app.d2lock.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log

/** Presentation lease only: never dismiss keyguard or change authentication results. */
internal object D2KeyguardPresentation {
    const val ACTION = "app.d2lock.action.KEYGUARD_PRESENTATION"
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var pending: (() -> Unit)? = null
    private var registered = false
    private val expiry = Runnable { restore("lease_expired") }

    fun register(app: Context) {
        if (registered || Build.VERSION.SDK_INT < 34) return
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val uid = runCatching { app.packageManager.getPackageUid("app.d2lock", 0) }.getOrNull() ?: return
                if (sentFromUid != uid || uid / 100000 != 0) return
                if (intent.getBooleanExtra("visible", false) &&
                    app.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                    deadline = SystemClock.elapsedRealtime() + 3_000L
                    main.removeCallbacks(expiry)
                    main.postDelayed(expiry, 3_000L)
                } else restore("surface_released")
            }
        }, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        registered = true
    }

    fun shouldSuppress(app: Context): Boolean = registered &&
        app.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
        SystemClock.elapsedRealtime() < deadline

    fun defer(resume: () -> Unit) {
        // Keep only the latest presentation request; never queue authentication events.
        pending = resume
        Log.i("D2Keyguard", "D2_KEYGUARD_PIN_DEFERRED healthy_surface=true")
    }

    private fun restore(reason: String) {
        deadline = 0L
        main.removeCallbacks(expiry)
        val resume = pending
        pending = null
        if (resume != null) runCatching { resume() }
            .onSuccess { Log.i("D2Keyguard", "D2_KEYGUARD_PIN_RESTORED reason=$reason") }
            .onFailure { Log.e("D2Keyguard", "D2_KEYGUARD_RESTORE_FAILED reason=$reason", it) }
    }
}
