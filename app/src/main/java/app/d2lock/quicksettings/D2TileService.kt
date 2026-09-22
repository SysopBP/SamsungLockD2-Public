package app.d2lock.quicksettings

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PinStore

class D2TileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "D2 Lock"
            subtitle = if (PinStore(this@D2TileService).configured()) "Ready" else "Setup required"
            state = if (PinStore(this@D2TileService).configured()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!PinStore(this).configured()) {
            qsTile?.apply { subtitle = "Create a D2 PIN first"; state = Tile.STATE_UNAVAILABLE; updateTile() }
            return
        }
        val intent = Intent(this, LockScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 4202, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
