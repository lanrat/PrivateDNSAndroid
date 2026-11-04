package ru.karasevm.privatednstoggle.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.service.AutoRevertReceiver
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertAt
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertMinutes
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertProvider
import java.util.Date
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns Private DNS back on a while after the user turned it off or to auto (upstream #59).
 *
 * The pending switch-back (time and server) is stored in shared preferences, so it survives
 * process death and reboots. It is carried out by an inexact alarm (no exact alarm permission
 * is needed), which Android may deliver several minutes late, so [catchUp] also runs it when
 * the tile or the app becomes visible after the deadline.
 */
object AutoRevert {
    private const val TAG = "AutoRevert"

    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440
    const val DEFAULT_MINUTES = 5

    /** Requested delivery window. Android 12+ may stretch windows below 10 minutes. */
    private const val ALARM_WINDOW_MS = 60_000L

    /** The alarm and a catch-up from the tile can run at the same time */
    private val revertMutex = Mutex()

    /** What a DNS change means for the switch-back */
    sealed interface Decision {
        /** Switch back to [provider] later */
        data class Schedule(val provider: String) : Decision

        /** Private DNS is on again, drop any pending switch-back */
        data object Cancel : Decision

        /** Keep whatever is pending */
        data object Keep : Decision
    }

    /**
     * Decides what to do after the user changed the setting from [previous] to [next].
     *
     * @param enabled whether the feature is enabled
     * @param mayStart whether this change may start a switch-back. False for requests from
     * other apps, so that automation that turns Private DNS off is not undone.
     */
    fun decide(
        previous: DnsSetting,
        next: DnsSetting,
        enabled: Boolean,
        mayStart: Boolean
    ): Decision {
        if (next.mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)) {
            return Decision.Cancel
        }
        val wasPrivate = previous.mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)
        val provider = previous.provider
        return if (enabled && mayStart && wasPrivate && !provider.isNullOrEmpty()) {
            Decision.Schedule(provider)
        } else {
            Decision.Keep
        }
    }

    /**
     * To be called after the app changed the system setting from [previous] to [next].
     * Schedules or cancels the switch-back as needed, and shows a toast when scheduling.
     *
     * @param mayStart false for requests from other apps, see [decide]
     */
    fun onDnsChanged(context: Context, previous: DnsSetting, next: DnsSetting, mayStart: Boolean) {
        val prefs = PreferenceHelper.defaultPreference(context)
        when (val decision = decide(previous, next, prefs.autoRevertEnabled, mayStart)) {
            is Decision.Schedule -> {
                val at = System.currentTimeMillis() + prefs.autoRevertMinutes * 60_000L
                prefs.autoRevertAt = at
                prefs.autoRevertProvider = decision.provider
                setAlarm(context, at)
                Log.i(TAG, "scheduled switch back to ${decision.provider} at ${Date(at)}")
                showToast(context, context.getString(R.string.auto_revert_scheduled_toast, formatTime(context, at)))
            }

            Decision.Cancel -> cancel(context)
            Decision.Keep -> Unit
        }
    }

    /** Drops any pending switch-back */
    fun cancel(context: Context) {
        val prefs = PreferenceHelper.defaultPreference(context)
        if (prefs.autoRevertAt == 0L) return
        Log.i(TAG, "cancelled pending switch back")
        prefs.autoRevertAt = 0L
        prefs.autoRevertProvider = ""
        alarmManager(context).cancel(alarmIntent(context))
    }

    /**
     * Gets the time of the pending switch-back in milliseconds since the epoch,
     * or null if none is pending.
     */
    fun pendingAt(context: Context): Long? =
        PreferenceHelper.defaultPreference(context).autoRevertAt.takeIf { it > 0L }

    /** Formats [time] as a short local time, e.g. "14:05" */
    fun formatTime(context: Context, time: Long): String =
        DateFormat.getTimeFormat(context).format(Date(time))

    /**
     * Runs the switch-back if it is due, otherwise makes sure the alarm is set.
     * Used after a reboot or app update (alarms are lost) and when the tile or app is shown.
     */
    suspend fun catchUp(context: Context) {
        val at = pendingAt(context) ?: return
        if (at <= System.currentTimeMillis()) {
            revertNow(context)
        } else {
            setAlarm(context, at)
        }
    }

    /**
     * Turns Private DNS back on with the stored server, unless the user already turned it on.
     */
    suspend fun revertNow(context: Context) = revertMutex.withLock {
        val prefs = PreferenceHelper.defaultPreference(context)
        val provider = prefs.autoRevertProvider
        if (prefs.autoRevertAt == 0L) return@withLock
        cancel(context)
        if (provider.isEmpty() || !PrivateDNSUtils.checkForPermission(context)) return@withLock

        val contentResolver = context.contentResolver
        val mode = PrivateDNSUtils.getPrivateMode(contentResolver)
        if (mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)) {
            Log.i(TAG, "revertNow: Private DNS is already on")
            return@withLock
        }
        try {
            PrivateDNSUtils.applyDnsSetting(
                contentResolver,
                DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, provider)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "revertNow: could not change settings", e)
            return@withLock
        }
        val repository = (context.applicationContext as PrivateDNSApp).repository
        val label = repository.getFirstByServer(provider)?.label?.ifBlank { null } ?: provider
        showToast(context, context.getString(R.string.set_to_provider_toast, label))
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AutoRevertReceiver::class.java).setAction(AutoRevertReceiver.ACTION_REVERT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Sets an inexact alarm for [at]. setWindow needs no exact alarm permission.
     */
    private fun setAlarm(context: Context, at: Long) {
        alarmManager(context).setWindow(
            AlarmManager.RTC_WAKEUP, at, ALARM_WINDOW_MS, alarmIntent(context)
        )
    }

    private fun showToast(context: Context, message: String) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
