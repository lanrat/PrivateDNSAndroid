package ru.karasevm.privatednstoggle.dns

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import ru.karasevm.privatednstoggle.service.AutoRevertReceiver
import java.util.Date

/** A pending switch back to Private DNS with [provider] at [at] (ms since the epoch) */
data class PendingSwitchBack(val at: Long, val provider: String)

/**
 * Stores the pending switch-back and arms the alarms that carry it out. An interface so
 * [DnsController] can be tested with a fake. Not thread-safe, [DnsController] serializes calls.
 */
interface SwitchBackScheduler {
    fun pending(): PendingSwitchBack?

    /** Stores [switchBack], replacing any pending one, and arms its alarms */
    fun schedule(switchBack: PendingSwitchBack)

    /** Arms the alarms of the stored switch-back again, e.g. after a reboot cleared them */
    fun rearm(switchBack: PendingSwitchBack)

    /** Drops the pending switch-back and its alarms */
    fun cancel()
}

/**
 * Keeps the pending switch-back in its own preferences file, which is excluded from backups,
 * so it survives process death and reboots, and carries it out with two alarms. Neither needs
 * the exact alarm permission:
 *  - [AlarmManager.setWindow]: on time to within about 10 minutes while the device is awake,
 *    but held back during Doze.
 *  - [AlarmManager.setAndAllowWhileIdle]: also delivered during Doze, but with a wider window.
 * Both deliver to [AutoRevertReceiver]; whichever comes first switches back and cancels both.
 */
class AlarmSwitchBackScheduler(context: Context) : SwitchBackScheduler {
    private val context = context.applicationContext
    private val state = this.context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)

    override fun pending(): PendingSwitchBack? {
        val at = state.getLong(KEY_AT, 0L)
        val provider = state.getString(KEY_PROVIDER, null)
        if (at > 0L && !provider.isNullOrEmpty()) return PendingSwitchBack(at, provider)
        // Incomplete state can't be carried out, drop it with any alarms
        if (state.all.isNotEmpty()) cancel()
        return null
    }

    override fun schedule(switchBack: PendingSwitchBack) {
        state.edit {
            putLong(KEY_AT, switchBack.at)
            putString(KEY_PROVIDER, switchBack.provider)
        }
        rearm(switchBack)
        Log.i(TAG, "scheduled switch back to ${switchBack.provider} at ${Date(switchBack.at)}")
    }

    override fun rearm(switchBack: PendingSwitchBack) {
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP, switchBack.at, ALARM_WINDOW_MS, alarmIntent(REQUEST_WINDOW_ALARM)
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, switchBack.at, alarmIntent(REQUEST_IDLE_ALARM)
        )
    }

    override fun cancel() {
        if (state.all.isEmpty()) return
        Log.i(TAG, "cancelled pending switch back")
        state.edit { clear() }
        alarmManager.cancel(alarmIntent(REQUEST_WINDOW_ALARM))
        alarmManager.cancel(alarmIntent(REQUEST_IDLE_ALARM))
    }

    private fun alarmIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AutoRevertReceiver::class.java).setAction(AutoRevertReceiver.ACTION_REVERT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private companion object {
        const val TAG = "SwitchBackScheduler"

        /** Requested delivery window. Android 12+ stretches windows below 10 minutes. */
        const val ALARM_WINDOW_MS = 60_000L

        // Excluded from backups in res/xml/backup_rules.xml and data_extraction_rules.xml
        const val STATE_PREFS = "auto_revert_state"
        const val KEY_AT = "at"
        const val KEY_PROVIDER = "provider"

        const val REQUEST_WINDOW_ALARM = 0
        const val REQUEST_IDLE_ALARM = 1
    }
}
