package app.d2lock.bridge

object LauncherBridgePolicy {
    fun canLock(enabled: Boolean, configured: Boolean, callActive: Boolean,
                authenticating: Boolean, force: Boolean, locked: Boolean): Boolean =
        enabled && configured && !callActive && !authenticating && (force || locked)
}
