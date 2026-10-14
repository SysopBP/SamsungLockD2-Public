/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.d2lock.root.ShellRunner

/** Explicit QS action; only D2-owned immersive setting is restored. */
class ImmersiveTileService : TileService() {
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onStartListening() { super.onStartListening(); refresh() }
    private fun refresh() { worker.execute {
        val r=runCatching { ShellRunner.run(listOf("su","-c","settings get global policy_control"),3) }.getOrNull()
        main.post { qsTile?.let { t -> t.state=if(r?.succeeded()!=true) Tile.STATE_UNAVAILABLE else if(r.output.trim()=="immersive.full=*") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;t.updateTile() } }
    } }
    override fun onClick() {
        super.onClick()
        unlockAndRun { worker.execute {
            val prefs=getSharedPreferences("immersive_tile",MODE_PRIVATE)
            val before=runCatching { ShellRunner.run(listOf("su","-c","settings get global policy_control"),3) }.getOrNull()
            if(before?.succeeded()!=true) { refresh();return@execute }
            val current=before.output.trim()
            val command=if(current=="immersive.full=*" && prefs.getBoolean("owns",false)) {
                val previous=prefs.getString("previous","null")!!
                if(previous=="null") "settings delete global policy_control" else "settings put global policy_control '${previous.replace("'","'\\''")}'"
            } else {
                prefs.edit().putString("previous",current).putBoolean("owns",true).commit()
                "settings put global policy_control 'immersive.full=*'"
            }
            val r=runCatching { ShellRunner.run(listOf("su","-c",command),3) }.getOrNull()
            if(r?.succeeded()==true && current=="immersive.full=*") prefs.edit().putBoolean("owns",false).apply()
            refresh()
        } }
    }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
