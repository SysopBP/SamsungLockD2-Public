package app.d2lock.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi

/** Rechecks opt-in when a previously issued lock capability is used. Never accepts preview extras. */
class IslandLockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PinUi.protect(this)
        if (IslandBridge.enabled(this) && PinStore(this).configured()) {
            IslandBridge.setLocked(this, true)
            startActivity(Intent(this, LockScreenActivity::class.java).putExtra("preview", false))
        }
        finish()
    }
}
