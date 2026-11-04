package ru.karasevm.privatednstoggle.util

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import ru.karasevm.privatednstoggle.R
import java.util.Date
import java.util.Locale

/**
 * Rules and formatting for switching Private DNS back on a while after the user turned it off
 * or to auto (upstream #59). [ru.karasevm.privatednstoggle.dns.DnsController] applies them and
 * [ru.karasevm.privatednstoggle.dns.SwitchBackScheduler] carries the switch-back out.
 */
object AutoRevert {
    /** Delays the user can choose from, in minutes */
    val PRESET_MINUTES = listOf(5, 15, 30, 60, 360, 1440)
    const val DEFAULT_MINUTES = 5

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

    /**
     * Formats a delay for display, e.g. "30 min" or "6 hr", using the platform's translations.
     */
    fun formatDuration(minutes: Int, locale: Locale = Locale.getDefault()): String {
        val measure = if (minutes % 60 == 0) {
            Measure(minutes / 60, MeasureUnit.HOUR)
        } else {
            Measure(minutes, MeasureUnit.MINUTE)
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(measure)
    }

    /** Formats [time] as a short local time, e.g. "14:05" */
    fun formatTime(context: Context, time: Long): String =
        DateFormat.getTimeFormat(context).format(Date(time))

    /** "Private DNS will turn back on at about 14:05" */
    fun scheduledMessage(context: Context, at: Long): String =
        context.getString(R.string.auto_revert_scheduled_toast, formatTime(context, at))
}
