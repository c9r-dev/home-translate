package app.screentranslate.live

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that turns live translate on and off. */
class LiveTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (LiveService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (LiveService.running) {
            startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_STOP))
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val intent = Intent(this, LiveStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
