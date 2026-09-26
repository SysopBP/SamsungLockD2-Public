package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Receives keyguard state emitted from D2's LSPosed code running inside SystemUI.
 * Exported only because the sender is a different process/package; reject any
 * sender that is not Android's system UID on platforms that expose sender UID.
 */
class KeyguardSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (Build.VERSION.SDK_INT >= 34 && sentFromUid != Process.SYSTEM_UID) {
            Log.w("SamsungLockD2", "GUARDIAN_XPOSED_SIGNAL_REJECTED uid=$sentFromUid")
            return
        }
        val state = intent.getStringExtra("state") ?: return
        val source = intent.getStringExtra("source") ?: "unknown"
        Log.i("SamsungLockD2", "GUARDIAN_XPOSED_KEYGUARD_RECEIVED state=$state source=$source")
        LockScreenService.keyguardSignal(context, state, source)
    }

    companion object {
        const val ACTION = "app.d2lock.action.XPOSED_KEYGUARD_STATE"
    }
}
