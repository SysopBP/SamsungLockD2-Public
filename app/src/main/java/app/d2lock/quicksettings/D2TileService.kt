package app.d2lock.quicksettings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.security.PinStore

class D2TileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val configured = PinStore(this).configured()
        qsTile?.apply {
            label = "D2 Lock"
            subtitle = if (configured) "Ready" else "Setup required"
            state = if (configured) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!PinStore(this).configured()) {
            qsTile?.apply {
                subtitle = "Create a D2 PIN first"
                state = Tile.STATE_UNAVAILABLE
                updateTile()
            }
            return
        }

        val intent = Intent(this, LockScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            4202,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            // Android 12/13 require the legacy Intent overload. It is deprecated only on newer APIs.
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
