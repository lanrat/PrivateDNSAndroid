package ru.karasevm.privatednstoggle.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.util.AutoRevert

/**
 * Carries out the pending switch-back of [AutoRevert] when its alarm fires, and restores the
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
                if (action == ACTION_REVERT) {
                    AutoRevert.revertNow(context)
                } else {
                    AutoRevert.catchUp(context)
                }
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
