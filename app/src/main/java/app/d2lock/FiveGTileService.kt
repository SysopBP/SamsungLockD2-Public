/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.d2lock.root.ShellRunner
class FiveGTileService: TileService() {
 private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
 private val main=android.os.Handler(android.os.Looper.getMainLooper())
 private fun run(mode: String) {
  worker.execute {
   val apk=applicationInfo.sourceDir.replace("'","'\\''")
   val r=runCatching { ShellRunner.run(listOf("su","-c","CLASSPATH='$apk' app_process /system/bin app.d2lock.root.FiveGWorker $mode"),5) }.getOrNull()
   main.post { qsTile?.let { tile -> tile.state=if(r?.succeeded()!=true) Tile.STATE_UNAVAILABLE else if(r.output.contains("NR=1")) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;tile.updateTile() } }
  }
 }
 override fun onStartListening() { super.onStartListening();run("status") }
 override fun onClick() { super.onClick();unlockAndRun { run("toggle") } }
 override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
