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
import ru.karasevm.privatednstoggle.util.AutoRevert
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

        /**
         * Boolean extra, true if another app requested the action. Such requests don't start
         * the automatic switch-back. The service is not exported, so only this app sets it.
         */
        const val EXTRA_FROM_OTHER_APP = "from_other_app"
        private const val TAG = "ShortcutService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.data?.toString()
        val fromOtherApp = intent?.getBooleanExtra(EXTRA_FROM_OTHER_APP, false) == true
        Log.d(TAG, "onStartCommand: got intent $data, from other app: $fromOtherApp")
        scope.launch {
            try {
                handleAction(data, fromOtherApp)
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
     * @param fromOtherApp whether another app requested the action
     */
    private suspend fun handleAction(action: String?, fromOtherApp: Boolean) {
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
        try {
            PrivateDNSUtils.applyDnsSetting(contentResolver, next)
        } catch (e: SecurityException) {
            // The permission was revoked after the check above
            Log.w(TAG, "handleAction: could not change settings", e)
            showToast(R.string.permission_missing)
            return
        }
        AutoRevert.onDnsChanged(
            this,
            DnsSetting(currentMode.orEmpty(), currentProvider),
            next,
            mayStart = !fromOtherApp
        )
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
