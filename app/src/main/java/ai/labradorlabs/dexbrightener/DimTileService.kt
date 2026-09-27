package ai.labradorlabs.dexbrightener

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings toggle. Useful because a visible overlay makes security-sensitive
 * views (those using filterTouchesWhenObscured) ignore taps, so being able to switch
 * it off in one tap matters.
 */
class DimTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        DimEngine.init(this)
        refresh()
    }

    override fun onClick() {
        DimEngine.init(this)
        val next = !DimEngine.prefs.current.enabled
        DimEngine.setEnabled(next)
        if (next) {
            if (!DimService.start(this)) {
                // Android refused the background service start; send the user to the app.
                startActivityAndCollapseCompat()
            }
        } else {
            DimService.stop(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = DimEngine.prefs.current.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) {
            val s = DimEngine.prefs.current
            val level = if (s.mode == DimMode.AUTO) DimEngine.autoLevel.value else s.defaultLevel
            "$level%"
        } else {
            "꺼짐"
        }
        tile.updateTile()
    }

    private fun startActivityAndCollapseCompat() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
