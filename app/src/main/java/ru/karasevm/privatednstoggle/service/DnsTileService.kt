package ru.karasevm.privatednstoggle.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.app
import ru.karasevm.privatednstoggle.dns.DnsChange
import ru.karasevm.privatednstoggle.ui.MainActivity
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE

/**
 * The quick settings tile. A tap moves to the next server or mode (see
 * [ru.karasevm.privatednstoggle.dns.DnsController.cycle]); the tile shows the current setting
 * and follows changes made anywhere while it is visible.
 */
class DnsTileService : TileService() {

    private val controller by lazy { app.dnsController }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null
    private var refreshJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!controller.hasPermission()) {
            return
        }
        // Require unlock to change mode according to user preference
        if (isLocked && app.preferences.requireUnlock) {
            unlockAndRun(this::cycleState)
        } else {
            cycleState()
        }
    }

    /**
     *  Moves to the next server or mode, then refreshes the tile.
     *  Opens the app instead if private mode is needed but no server is enabled.
     */
    private fun cycleState() {
        scope.launch {
            when (controller.cycle()) {
                DnsChange.NoEnabledServer -> {
                    Log.i(TAG, "cycleState: no enabled servers, opening the app")
                    withContext(Dispatchers.Main) { openApp() }
                }
                // A revoked permission shows as an unavailable tile after the refresh
                else -> withContext(Dispatchers.Main) { refreshTile() }
            }
        }
    }

    /**
     * Opens the main activity and collapses the quick settings panel.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        // Prevent some crashes
        if (qsTile == null) {
            Log.w(TAG, "onStartListening: qsTile is null")
            return
        }

        // Follow changes while visible, whether made by this app, the system Settings or others
        listeningJob?.cancel()
        listeningJob = scope.launch(Dispatchers.Main) {
            controller.changes().collect { refreshTile() }
        }
        refreshTile()
        // Run a switch-back whose alarm was delayed, then show the result
        scope.launch {
            if (controller.runDueSwitchBack() != null) {
                withContext(Dispatchers.Main) { refreshTile() }
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     *  Refreshes the state of the tile from the system settings.
     *  Tile updates run on the main thread and a newer refresh cancels a pending older one,
     *  so a slow server lookup can't overwrite a newer state.
     */
    private fun refreshTile() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) { updateTileFromSettings() }
    }

    private suspend fun updateTileFromSettings() {
        val (isPermissionGranted, setting, pendingAt) = withContext(Dispatchers.IO) {
            Triple(controller.hasPermission(), controller.current(), controller.pendingSwitchBackAt())
        }
        val inactive = if (isPermissionGranted) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        // While Private DNS is not on, show when it will be switched back on
        val switchBackSubtitle = pendingAt?.let {
            getString(R.string.auto_revert_tile_subtitle, AutoRevert.formatTime(this, it))
        }
        when (setting.mode.lowercase()) {
            DNS_MODE_OFF -> setTile(
                inactive, getString(R.string.dns_off), R.drawable.ic_off_black_24dp, switchBackSubtitle
            )

            DNS_MODE_AUTO -> setTile(
                inactive, getString(R.string.dns_auto), R.drawable.ic_auto_black_24dp, switchBackSubtitle
            )

            DNS_MODE_PRIVATE -> setTile(
                if (isPermissionGranted) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE,
                // the server's label, or its address if it has none or isn't saved
                setting.provider?.let { withContext(Dispatchers.IO) { controller.displayName(it) } }
                    ?: getString(R.string.dns_unknown),
                R.drawable.ic_private_black_24dp
            )

            else -> setTile(
                inactive, getString(R.string.dns_unknown), R.drawable.ic_unknown_black_24dp, switchBackSubtitle
            )
        }
    }

    /**
     * Updates the tile to the specified parameters.
     * Does nothing if the tile is not available (not listening).
     *
     * @param state tile state
     * @param label tile label
     * @param icon tile icon
     * @param subtitle second line of the tile (Android 10+), null for none
     */
    private fun setTile(state: Int, label: String?, icon: Int, subtitle: String? = null) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.icon = Icon.createWithResource(this, icon)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "DnsTileService"
    }
}
