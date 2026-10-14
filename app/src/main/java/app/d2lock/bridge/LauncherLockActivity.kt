package app.d2lock.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.d2lock.Prefs
import app.d2lock.lockscreen.GuardianWatchdog
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.notifications.CallNotificationStore
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi

/** Rechecks D2 state when the immutable capability is used. No preview or unlock extras. */
class LauncherLockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PinUi.protect(this)
        val force = intent.getBooleanExtra("force_lock", false)
        if (LauncherBridgePolicy.canLock(Prefs.enabled(this), PinStore(this).configured(),
                CallNotificationStore.isCallActive(this), GuardianWatchdog.isTrustedAuthenticationActive(),
                force, IslandBridge.locked(this))) {
            IslandBridge.setLocked(this, true)
            startActivity(Intent(this, LockScreenActivity::class.java)
                .putExtra("launcher_sleep", force)
                .putExtra("preview", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}
