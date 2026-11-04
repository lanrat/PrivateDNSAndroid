package ru.karasevm.privatednstoggle.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.service.AutoRevertReceiver
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertMinutes
import java.util.Date

/**
 * Turns Private DNS back on a while after the user turned it off or to auto (upstream #59).
 *
 * The pending switch-back (deadline and server) is stored in its own preferences file, which is
 * excluded from backups, so it survives process death and reboots. Two alarms carry it out;
 * neither needs the exact alarm permission:
 *  - [AlarmManager.setWindow]: on time to within about 10 minutes while the device is awake,
 *    but held back during Doze.
 *  - [AlarmManager.setAndAllowWhileIdle]: also delivered during Doze, but with a wider window.
 * Whichever fires first switches back and cancels the other. A switch-back that is due also
 * runs when the tile or the app becomes visible ([catchUp]).
 *
 * Every change made by the user through the app goes through [applyUserChange], which shares
 * a lock with [catchUp], so a switch-back can't interleave with a tap and undo it.
 */
object AutoRevert {
    private const val TAG = "AutoRevert"

    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440
    const val DEFAULT_MINUTES = 5

    /** Requested delivery window. Android 12+ stretches windows below 10 minutes. */
    private const val ALARM_WINDOW_MS = 60_000L

    private const val STATE_PREFS = "auto_revert_state"
    private const val KEY_AT = "at"
    private const val KEY_PROVIDER = "provider"

    private const val REQUEST_WINDOW_ALARM = 0
    private const val REQUEST_IDLE_ALARM = 1

    private val lock = Any()

    /** What a DNS change means for the switch-back */
    sealed interface Decision {
        /** Switch back to [provider] later */
        data class Schedule(val provider: String) : Decision

        /** Drop any pending switch-back */
        data object Cancel : Decision

        /** Keep whatever is pending */
        data object Keep : Decision
    }

    /**
     * Decides what to do after the setting changed from [previous] to [next].
     *
     * @param enabled whether the feature is enabled
     * @param fromOtherApp whether another app requested the change. Such a change never starts
     * a switch-back, and it cancels a pending one, so that automations stay in control.
     */
    fun decide(
        previous: DnsSetting,
        next: DnsSetting,
        enabled: Boolean,
        fromOtherApp: Boolean
    ): Decision {
        if (fromOtherApp || isPrivate(next.mode)) {
            return Decision.Cancel
        }
        val provider = previous.provider
        return if (enabled && isPrivate(previous.mode) && !provider.isNullOrEmpty()) {
            Decision.Schedule(provider)
        } else {
            Decision.Keep
        }
    }

    private fun isPrivate(mode: String?) =
        mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)

    private fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /**
     * Applies a change the user requested through the app, then schedules or cancels the
     * switch-back as needed and shows a toast when scheduling.
     *
     * @param fromOtherApp true for requests from other apps, see [decide]
     */
    fun applyUserChange(context: Context, next: DnsSetting, fromOtherApp: Boolean) {
        val scheduledAt = synchronized(lock) {
            val contentResolver = context.contentResolver
            val previous = DnsSetting(
                PrivateDNSUtils.getPrivateMode(contentResolver).orEmpty(),
                PrivateDNSUtils.getPrivateProvider(contentResolver)
            )
            PrivateDNSUtils.applyDnsSetting(contentResolver, next)
            val enabled = PreferenceHelper.defaultPreference(context).autoRevertEnabled
            when (val decision = decide(previous, next, enabled, fromOtherApp)) {
                is Decision.Schedule -> schedule(context, decision.provider)
                Decision.Cancel -> {
                    cancelLocked(context)
                    null
                }

                Decision.Keep -> null
            }
        }
        if (scheduledAt != null) {
            showToast(
                context,
                context.getString(R.string.auto_revert_scheduled_toast, formatTime(context, scheduledAt))
            )
        }
    }

    /** Stores and arms a switch-back to [provider], returns its deadline. Call under [lock]. */
    private fun schedule(context: Context, provider: String): Long {
        val minutes = PreferenceHelper.defaultPreference(context).autoRevertMinutes
        val at = System.currentTimeMillis() + minutes * 60_000L
        state(context).edit {
            putLong(KEY_AT, at)
            putString(KEY_PROVIDER, provider)
        }
        setAlarms(context, at)
        Log.i(TAG, "scheduled switch back to $provider at ${Date(at)}")
        return at
    }

    /** Drops any pending switch-back */
    fun cancel(context: Context) = synchronized(lock) { cancelLocked(context) }

    /**
     * Drops the pending switch-back if it would switch to [server], e.g. because that server
     * was deleted, disabled or changed.
     */
    fun cancelIfProvider(context: Context, server: String) = synchronized(lock) {
        if (state(context).getString(KEY_PROVIDER, null) == server) cancelLocked(context)
    }

    private fun cancelLocked(context: Context) {
        val state = state(context)
        if (!state.contains(KEY_AT)) return
        Log.i(TAG, "cancelled pending switch back")
        state.edit { clear() }
        val alarmManager = alarmManager(context)
        alarmManager.cancel(alarmIntent(context, REQUEST_WINDOW_ALARM))
        alarmManager.cancel(alarmIntent(context, REQUEST_IDLE_ALARM))
    }

    /**
     * Gets the time of the pending switch-back in milliseconds since the epoch,
     * or null if none is pending.
     */
    fun pendingAt(context: Context): Long? =
        state(context).getLong(KEY_AT, 0L).takeIf { it > 0L }

    /** Formats [time] as a short local time, e.g. "14:05" */
    fun formatTime(context: Context, time: Long): String =
        DateFormat.getTimeFormat(context).format(Date(time))

    /**
     * Runs the switch-back if it is due, otherwise makes sure its alarms are set.
     * Used when an alarm fires, after a reboot (alarms are lost), after a force stop, and when
     * the tile or app is shown.
     *
     * @return true if Private DNS was switched back on
     */
    suspend fun catchUp(context: Context): Boolean {
        val provider = synchronized(lock) {
            val state = state(context)
            val at = state.getLong(KEY_AT, 0L)
            val provider = state.getString(KEY_PROVIDER, null)
            when {
                at == 0L -> return false
                at > System.currentTimeMillis() -> {
                    // Not due yet, e.g. an old alarm after the user scheduled a new one
                    setAlarms(context, at)
                    return false
                }
            }
            cancelLocked(context)
            if (provider.isNullOrEmpty() || !PrivateDNSUtils.checkForPermission(context)) {
                return false
            }
            val contentResolver = context.contentResolver
            if (isPrivate(PrivateDNSUtils.getPrivateMode(contentResolver))) {
                Log.i(TAG, "catchUp: Private DNS is already on")
                return false
            }
            try {
                PrivateDNSUtils.applyDnsSetting(
                    contentResolver,
                    DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, provider)
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "catchUp: could not change settings", e)
                return false
            }
            provider
        }
        Log.i(TAG, "switched back to $provider")
        // Android 13+ only shows toasts from the background when notifications are allowed,
        // so this mostly shows when the app itself is open. The tile shows the state anyway.
        val repository = (context.applicationContext as PrivateDNSApp).repository
        val label = repository.getFirstByServer(provider)?.label?.ifBlank { null } ?: provider
        showToast(context, context.getString(R.string.set_to_provider_toast, label))
        return true
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AutoRevertReceiver::class.java).setAction(AutoRevertReceiver.ACTION_REVERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Arms both alarms for [at], see the class documentation */
    private fun setAlarms(context: Context, at: Long) {
        val alarmManager = alarmManager(context)
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP, at, ALARM_WINDOW_MS, alarmIntent(context, REQUEST_WINDOW_ALARM)
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at, alarmIntent(context, REQUEST_IDLE_ALARM)
        )
    }

    private fun showToast(context: Context, message: String) {
        val appContext = context.applicationContext
        val show = { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        // Show right away on the main thread, while a calling activity is still in front
        if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
    }
}
