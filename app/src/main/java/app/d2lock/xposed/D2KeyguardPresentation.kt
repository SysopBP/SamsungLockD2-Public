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

/** Short presentation lease and signer-verified, session-bound D2 authentication handoff. */
internal object D2KeyguardPresentation {
    const val ACTION = "app.d2lock.action.KEYGUARD_PRESENTATION"
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var pending: (() -> Unit)? = null
    private var registered = false
    private var session: String? = null
    private var consumed = false
    private var dismiss: (() -> Unit)? = null

    fun bindDismiss(callback: () -> Unit) { dismiss = callback }
    private val expiry = Runnable { restore("lease_expired") }

    fun register(app: Context) {
        if (registered || Build.VERSION.SDK_INT < 34) return
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val uid = runCatching { app.packageManager.getPackageUid("app.d2lock", 0) }.getOrNull() ?: return
                if (sentFromUid != uid || uid / 100000 != 0) return
                // Shared UIDs cannot establish which package authenticated the user.
                if (app.packageManager.getPackagesForUid(uid)?.toList() != listOf("app.d2lock")) return
                val info = app.packageManager.getPackageInfo("app.d2lock", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                val trusted = info.signingInfo?.apkContentsSigners?.any {
                    java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte) } ==
                        "7ce7d3e590520bcd54dfd9760f36e5fdc6243b1b062c46cac4670b65efb0f370"
                } == true
                if (!trusted) return
                if (intent.action == "app.d2lock.action.KEYGUARD_AUTHENTICATED") {
                    val requested = intent.getStringExtra("session") ?: return
                    if (requested != session || consumed || !shouldSuppress(app)) return
                    if (app.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
                    val callback = dismiss ?: return
                    // Boot, lockdown, biometric lockout and other strong-auth requirements
                    // remain Samsung's responsibility, even after CE storage is available.
                    val allowed = runCatching {
                        val currentUser = android.app.ActivityManager::class.java.getDeclaredMethod("getCurrentUser")
                            .apply { isAccessible = true }.invoke(null) as Int
                        if (currentUser != 0) return@runCatching false
                        val type = Class.forName("com.android.internal.widget.LockPatternUtils")
                        val utils = type.getConstructor(Context::class.java).newInstance(app)
                        type.getMethod("getStrongAuthForUser", Int::class.javaPrimitiveType).invoke(utils, 0) == 0
                    }.getOrDefault(false)
                    if (!allowed) { Log.w("D2Keyguard", "D2_KEYGUARD_HANDOFF_DENIED strong_auth=true"); return }
                    consumed = true
                    runCatching { callback() }
                        .onSuccess {
                            pending = null
                            Log.i("D2Keyguard", "D2_KEYGUARD_HANDOFF_REQUESTED")
                        }
                        .onFailure { Log.e("D2Keyguard", "D2_KEYGUARD_HANDOFF_FAILED", it) }
                    return
                }
                if (intent.getBooleanExtra("visible", false) &&
                    app.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                    val next = intent.getStringExtra("session") ?: return
                    if (session != next) { session = next; consumed = false }
                    deadline = SystemClock.elapsedRealtime() + 3_000L
                    main.removeCallbacks(expiry)
                    main.postDelayed(expiry, 3_000L)
                } else restore("surface_released")
            }
        }, IntentFilter(ACTION).apply { addAction("app.d2lock.action.KEYGUARD_AUTHENTICATED") }, Context.RECEIVER_EXPORTED)
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
        session = null
        main.removeCallbacks(expiry)
        val resume = pending
        pending = null
        if (resume != null) runCatching { resume() }
            .onSuccess { Log.i("D2Keyguard", "D2_KEYGUARD_PIN_RESTORED reason=$reason") }
            .onFailure { Log.e("D2Keyguard", "D2_KEYGUARD_RESTORE_FAILED reason=$reason", it) }
    }
}
