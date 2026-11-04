package ru.karasevm.privatednstoggle.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.app

/**
 * Carries out the pending switch-back (see [ru.karasevm.privatednstoggle.dns.DnsController.runDueSwitchBack]) when its alarm fires, and restores the
 * alarm after a reboot or app update, which clear alarms.
 */
class AutoRevertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")
        val action = intent.action
        if (action != ACTION_REVERT &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Checks the deadline, so an outdated alarm just re-arms the current one.
                // No toast: Android 13+ hides them from the background without the
                // notification permission. The tile shows the result.
                context.app.dnsController.runDueSwitchBack()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoRevertReceiver"
        const val ACTION_REVERT = "ru.karasevm.privatednstoggle.action.AUTO_REVERT"
    }
}
