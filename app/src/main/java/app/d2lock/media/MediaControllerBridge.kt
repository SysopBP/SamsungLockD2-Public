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

    private fun controller(): MediaController? = runCatching {
        val sessions = manager.getActiveSessions(listener)
        sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            ?: sessions.firstOrNull()
    }.getOrNull()
    fun isPlaying() = controller()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
    fun title(): String = controller()?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    fun artist(): String = controller()?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
    fun album(): String = controller()?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    fun artwork() = controller()?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: controller()?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    fun durationMs(): Long = controller()?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    fun positionMs(): Long {
        val c = controller() ?: return 0L
        val state = c.playbackState ?: return 0L
        val base = state.position.coerceAtLeast(0L)
        if (state.state != android.media.session.PlaybackState.STATE_PLAYING) return base
        val elapsed = (android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (base + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
    }
    fun previous() = controller()?.transportControls?.skipToPrevious()
    fun toggle() = controller()?.let {
        if (it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) it.transportControls.pause()
        else it.transportControls.play()
    }
    fun next() = controller()?.transportControls?.skipToNext()
}
