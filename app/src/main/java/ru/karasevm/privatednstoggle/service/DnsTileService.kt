package ru.karasevm.privatednstoggle.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.ui.MainActivity
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.checkForPermission

class DnsTileService : TileService() {

    private val repository: DnsServerRepository by lazy { (application as PrivateDNSApp).repository }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sharedPreferences by lazy { PreferenceHelper.defaultPreference(this) }
    private var isObserverRegistered = false
    private var refreshJob: Job? = null

    /**
     * Refreshes the tile whenever the system Private DNS settings change while the tile is
     * visible, whether the change came from this app, the system Settings app or anything else.
     */
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshTile()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    /**
     *  Sets the system settings to the next state, then refreshes the tile.
     *  Opens the app instead if private mode is needed but no server is enabled.
     */
    private fun cycleState() {
        scope.launch {
            val next = PrivateDNSUtils.getNextProvider(
                sharedPreferences.autoMode,
                PrivateDNSUtils.getPrivateMode(contentResolver),
                PrivateDNSUtils.getPrivateProvider(contentResolver),
                repository
            )
            if (next == null) {
                Log.i(TAG, "cycleState: no enabled servers, opening the app")
                withContext(Dispatchers.Main) { openApp() }
                return@launch
            }
            PrivateDNSUtils.applyDnsSetting(contentResolver, next)
            // The settings observer refreshes too, but only if the value actually changed
            withContext(Dispatchers.Main) { refreshTile() }
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

    override fun onClick() {
        super.onClick()
        if (!checkForPermission(this)) {
            return
        }

        // Require unlock to change mode according to user preference
        val requireUnlock = sharedPreferences.requireUnlock
        if (isLocked && requireUnlock) {
            unlockAndRun(this::cycleState)
        } else {
            cycleState()
        }
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
        val isPermissionGranted = checkForPermission(this)
        val dnsMode = PrivateDNSUtils.getPrivateMode(contentResolver)
        when (dnsMode?.lowercase()) {
            DNS_MODE_OFF -> {
                setTile(
                    if (!isPermissionGranted) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE,
                    getString(R.string.dns_off),
                    R.drawable.ic_off_black_24dp
                )
            }

            DNS_MODE_AUTO -> {
                setTile(
                    if (!isPermissionGranted) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE,
                    getString(R.string.dns_auto),
                    R.drawable.ic_auto_black_24dp
                )
            }

            DNS_MODE_PRIVATE -> {
                val activeAddress = PrivateDNSUtils.getPrivateProvider(contentResolver)
                val dnsServer = activeAddress?.let {
                    withContext(Dispatchers.IO) { repository.getFirstByServer(it) }
                }
                setTile(
                    if (!isPermissionGranted) Tile.STATE_UNAVAILABLE else Tile.STATE_ACTIVE,
                    // display server address if either there is no label or the server is not known
                    dnsServer?.label?.ifBlank { null }
                        ?: activeAddress
                        ?: getString(R.string.dns_unknown),
                    R.drawable.ic_private_black_24dp
                )
            }

            else -> {
                setTile(
                    if (!isPermissionGranted) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE,
                    getString(R.string.dns_unknown),
                    R.drawable.ic_unknown_black_24dp
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        // Prevent some crashes
        if (qsTile == null) {
            Log.w(TAG, "onStartListening: qsTile is null")
            return
        }

        if (!isObserverRegistered) {
            contentResolver.registerContentObserver(
                PrivateDNSUtils.PRIVATE_DNS_MODE_URI, false, settingsObserver
            )
            contentResolver.registerContentObserver(
                PrivateDNSUtils.PRIVATE_DNS_PROVIDER_URI, false, settingsObserver
            )
            isObserverRegistered = true
        }
        refreshTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        if (isObserverRegistered) {
            contentResolver.unregisterContentObserver(settingsObserver)
            isObserverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isObserverRegistered) {
            contentResolver.unregisterContentObserver(settingsObserver)
            isObserverRegistered = false
        }
        scope.cancel()
    }

    /**
     * Updates the tile to the specified parameters.
     * Does nothing if the tile is not available (not listening).
     *
     * @param state tile state
     * @param label tile label
     * @param icon tile icon
     */
    private fun setTile(state: Int, label: String?, icon: Int) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.icon = Icon.createWithResource(this, icon)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "DnsTileService"
    }
}
