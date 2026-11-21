package lab.hyperaudio

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A tile starts an Activity so every new capture still uses the system consent UI. */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = android.graphics.drawable.Icon.createWithResource(this@CaptureTileService,R.drawable.lab_icon)
            state = if (LabState.running || ReceiverState.snapshot.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "HyperAudio"
            subtitle = if (AppRole.current(this@CaptureTileService)==AudioRole.RECEIVE) { if(ReceiverState.snapshot.running) "点击停止接收" else "点击开始接收" } else if (LabState.running) "点击停止发送" else "点击连接并授权"
            updateTile()
        }
    }
    // API 29–33 has no PendingIntent overload. The deprecated call is never reached on API 34+.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBefore34(intent: Intent) { startActivityAndCollapse(intent) }
    override fun onClick() {
        super.onClick()
        if(ReceiverState.snapshot.running){
            startService(Intent(this,ReceiverService::class.java).setAction(ReceiverService.ACTION_STOP));return
        }
        if (LabState.running) {
            startService(Intent(this,CaptureService::class.java).setAction("STOP"))
            return
        }
        unlockAndRun {
            val intent = Intent(this,MainActivity::class.java).setAction("CONNECT_FROM_TILE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
                PendingIntent.getActivity(this,42,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else {
                launchBefore34(intent)
            }
        }
    }
}
