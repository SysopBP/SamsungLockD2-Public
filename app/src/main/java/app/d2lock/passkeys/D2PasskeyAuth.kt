package app.d2lock.passkeys

import android.app.Activity
import app.d2lock.Prefs
import app.d2lock.security.PatternStore
import app.d2lock.security.PatternUi
import app.d2lock.security.PinStore
import app.d2lock.security.PinUi

/**
 * One authorization entry point for sensitive passkey operations.
 * Uses the user's selected D2 Pattern/PIN and retains PIN as recovery.
 */
object D2PasskeyAuth {
    fun configured(activity: Activity): Boolean =
        PinStore(activity).configured() ||
            (Prefs.unlockMethod(activity) == "pattern" && PatternStore(activity).configured())

    fun authorize(
        activity: Activity,
        success: () -> Unit,
        cancel: () -> Unit = {}
    ) {
        PinUi.protect(activity)
        val pin = { PinUi.show(activity, success = success, cancel = cancel) }
        if (Prefs.unlockMethod(activity) == "pattern" && PatternStore(activity).configured()) {
            PatternUi.show(activity, success = success, usePin = { pin() }, cancel = cancel)
        } else {
            pin()
        }
    }
}
