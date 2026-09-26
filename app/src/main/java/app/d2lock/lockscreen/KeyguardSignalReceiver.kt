package app.d2lock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives keyguard state emitted from D2's LSPosed code running inside SystemUI.
 * The exported receiver is protected by android.permission.STATUS_BAR in the
 * manifest, so only privileged SystemUI/system senders can reach this code.
 * Android 17 may report sentFromUid=-1 for this explicit cross-process broadcast;
 * do not reject that framework sentinel after the manifest permission gate.
 */
class KeyguardSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val senderUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
        Log.i("SamsungLockD2", "GUARDIAN_XPOSED_SIGNAL_ACCEPTED uid=$senderUid permission=STATUS_BAR")
        val state = intent.getStringExtra("state") ?: return
        val source = intent.getStringExtra("source") ?: "unknown"
        Log.i("SamsungLockD2", "GUARDIAN_XPOSED_KEYGUARD_RECEIVED state=$state source=$source")
        context.getSharedPreferences("guardian_xposed_health", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_systemui_event_ms", System.currentTimeMillis())
            .putString("last_systemui_state", state)
            .putString("last_systemui_source", source)
            .apply()
        if (state != "HEARTBEAT") {
            LockScreenService.keyguardSignal(context, state, source)
        }
    }

    companion object {
        const val ACTION = "app.d2lock.action.XPOSED_KEYGUARD_STATE"
    }
}
