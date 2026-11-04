package ru.karasevm.privatednstoggle.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

/**
 * Performs shortcut actions. Started by [ru.karasevm.privatednstoggle.ShortcutHandlerActivity]
 * with one of the ACTION_* URIs as the intent data.
 */
class ShortcutService : Service() {

    private val repository: DnsServerRepository by lazy { (application as PrivateDNSApp).repository }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_DO_CYCLE = "privatednstoggle://do_cycle" // regular cycle
        const val ACTION_SWITCH_MODE = "privatednstoggle://switch_mode" // toggle private and non-private modes
        const val ACTION_TURN_ON = "privatednstoggle://turn_on"
        const val ACTION_TURN_OFF = "privatednstoggle://turn_off"

        /** All supported actions */
        val ACTIONS = listOf(ACTION_DO_CYCLE, ACTION_SWITCH_MODE, ACTION_TURN_ON, ACTION_TURN_OFF)
        private const val TAG = "ShortcutService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.data?.toString()
        Log.d(TAG, "onStartCommand: got intent $data")
        scope.launch {
            try {
                handleAction(data)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Computes and applies the new dns setting for [action].
     *
     * @param action one of the ACTION_* constants
     */
    private suspend fun handleAction(action: String?) {
        if (!PrivateDNSUtils.checkForPermission(this)) {
            showToast(R.string.permission_missing)
            return
        }
        val autoMode = PreferenceHelper.defaultPreference(this).autoMode
        val currentMode = PrivateDNSUtils.getPrivateMode(contentResolver)
        val currentProvider = PrivateDNSUtils.getPrivateProvider(contentResolver)

        val next: DnsSetting? = when (action) {
            ACTION_DO_CYCLE -> PrivateDNSUtils.getNextProvider(
                autoMode, currentMode, currentProvider, repository
            )

            ACTION_SWITCH_MODE -> PrivateDNSUtils.getNextMode(
                autoMode, currentMode, currentProvider, repository
            )

            ACTION_TURN_ON -> {
                // Keep the current provider if it is a known, enabled server
                val provider = if (!currentProvider.isNullOrEmpty() &&
                    repository.getFirstByServer(currentProvider)?.enabled == true
                ) {
                    currentProvider
                } else {
                    repository.getFirstEnabled()?.server
                }
                provider?.let { DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, it) }
            }

            ACTION_TURN_OFF -> DnsSetting(PrivateDNSUtils.DNS_MODE_OFF, currentProvider)

            else -> {
                Log.w(TAG, "handleAction: unknown action $action")
                return
            }
        }

        if (next == null) {
            // Private mode is needed, but there is no enabled server to use
            showToast(R.string.no_servers_added)
            return
        }
        PrivateDNSUtils.applyDnsSetting(contentResolver, next)
    }

    private suspend fun showToast(@StringRes message: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(this@ShortcutService, message, Toast.LENGTH_SHORT).show()
    }

    // The scope is deliberately not cancelled in onDestroy: stopSelf(startId) from the newest
    // request can destroy the service while an older, short-lived request is still finishing.

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
