package app.d2lock.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import app.d2lock.notifications.LockNotificationListener

class MediaControllerBridge(context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, LockNotificationListener::class.java)

    private fun controller(): MediaController? = runCatching { manager.getActiveSessions(listener).firstOrNull() }.getOrNull()
    fun title(): String = controller()?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    fun artist(): String = controller()?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
    fun previous() = controller()?.transportControls?.skipToPrevious()
    fun toggle() = controller()?.let {
        if (it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) it.transportControls.pause()
        else it.transportControls.play()
    }
    fun next() = controller()?.transportControls?.skipToNext()
}
