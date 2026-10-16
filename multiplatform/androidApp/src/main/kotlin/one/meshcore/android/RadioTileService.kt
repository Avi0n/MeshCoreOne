package one.meshcore.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_radio_tile_label
import org.jetbrains.compose.resources.getString

/**
 * Quick Settings tile for radio connect/disconnect (Control widget equivalent).
 */
class RadioTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refreshTile() }
    }

    override fun onClick() {
        super.onClick()
        val app = application as? MeshCoreApplication ?: return
        scope.launch {
            val container = app.container
            when (radioTileClickAction(container.appState.connectionState.value)) {
                RadioTileClickAction.Disconnect -> container.disconnect()
                RadioTileClickAction.Reconnect -> container.reconnectLastDevice()
                RadioTileClickAction.OpenApp -> collapseToApp()
            }
            refreshTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refreshTile() {
        val label = getString(Res.string.android_radio_tile_label)
        val tile = qsTile ?: return
        val app = application as? MeshCoreApplication
        val state = app?.container?.appState?.connectionState?.value
        tile.state = if (state != null && radioTileIsActive(state)) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.label = label
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun collapseToApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val OPEN_APP_REQUEST_CODE = 0

        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, RadioTileService::class.java))
        }
    }
}
