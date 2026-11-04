package ru.karasevm.privatednstoggle.dns

import android.util.Log
import kotlinx.coroutines.flow.Flow
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.util.AppPreferences
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils
import ru.karasevm.privatednstoggle.util.ShortcutActions

/** The outcome of a requested Private DNS change */
sealed interface DnsChange {
    /**
     * [setting] was applied.
     * @property switchBackAt when Private DNS will be switched back on, if this change
     * scheduled that (ms since the epoch)
     */
    data class Applied(val setting: DnsSetting, val switchBackAt: Long? = null) : DnsChange

    /** Private DNS would have to be turned on, but no server is enabled */
    data object NoEnabledServer : DnsChange

    /** "Off for…" needs a hostname to switch back to, and there is none */
    data object NothingToSwitchBackTo : DnsChange

    /** The app doesn't have the WRITE_SECURE_SETTINGS permission (any more) */
    data object PermissionMissing : DnsChange
}

/**
 * Makes all changes to the system Private DNS setting: the tile, the shortcuts, the server
 * picker and the timed switch-back (upstream #59) go through here.
 *
 * Every change reads the current setting, writes the new one and updates the pending
 * switch-back under one lock, so a switch-back that comes due can't interleave with a tap and
 * undo it. The UI decides how to report the returned [DnsChange].
 *
 * The dependencies are interfaces, so this is tested on the JVM with fakes.
 */
class DnsController(
    private val settings: SystemDnsSettings,
    private val repository: DnsServerRepository,
    private val preferences: AppPreferences,
    private val switchBack: SwitchBackScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun hasPermission(): Boolean = settings.hasPermission()

    /** The current system setting */
    fun current(): DnsSetting = settings.read()

    /** Emits whenever the system setting changes, while collected */
    fun changes(): Flow<Unit> = settings.changes()

    /** Moves to the next server or mode, like tapping the tile */
    suspend fun cycle(): DnsChange = changeTo(fromOtherApp = false) { current ->
        PrivateDNSUtils.getNextProvider(preferences.autoMode, current.mode, current.provider, repository)
    }

    /**
     * Performs a shortcut action, see [ShortcutActions].
     *
     * @param fromOtherApp whether another app requested it. Such changes never start a
     * switch-back and cancel a pending one, see [AutoRevert.decide].
     */
    suspend fun performShortcut(action: String, fromOtherApp: Boolean): DnsChange =
        changeTo(fromOtherApp) { current ->
            ShortcutActions.nextSetting(action, preferences.autoMode, current.mode, current.provider, repository)
        }

    /** Applies a setting the user picked, e.g. in the server picker */
    fun select(next: DnsSetting): DnsChange = apply(next, fromOtherApp = false)

    /**
     * Turns Private DNS off now and back on after [minutes] with the current hostname, whether
     * or not the automatic switch-back is enabled. Used by "Off for…" in the server picker.
     */
    fun turnOffFor(minutes: Int): DnsChange = permissionChecked {
        synchronized(lock) {
            val provider = switchBackTarget() ?: return@synchronized DnsChange.NothingToSwitchBackTo
            val off = DnsSetting(PrivateDNSUtils.DNS_MODE_OFF, provider)
            settings.write(off)
            DnsChange.Applied(off, scheduleLocked(provider, minutes))
        }
    }

    /**
     * The hostname [turnOffFor] would switch back to: the current one, which Android keeps
     * while the mode is off or auto. Null if there is none.
     */
    fun switchBackTarget(): String? = settings.read().provider?.ifBlank { null }

    /** When the pending switch-back is due (ms since the epoch), null if there is none */
    fun pendingSwitchBackAt(): Long? = switchBack.pending()?.at

    /** Drops the pending switch-back, e.g. when the user disables the feature */
    fun cancelSwitchBack() = synchronized(lock) { switchBack.cancel() }

    /**
     * Drops the pending switch-back if it would switch to [server], e.g. because that server
     * was deleted, disabled or changed. Host names are compared ignoring case.
     */
    fun cancelSwitchBackTo(server: String) = synchronized(lock) {
        if (switchBack.pending()?.provider.equals(server, ignoreCase = true)) switchBack.cancel()
    }

    /**
     * Runs the pending switch-back if it is due, otherwise re-arms its alarms. Used when an
     * alarm fires, after a reboot (alarms are lost) or a force stop, and when the tile or app
     * is shown, since the alarms may be late.
     *
     * @return the applied setting if Private DNS was switched back on
     */
    fun runDueSwitchBack(): DnsSetting? = synchronized(lock) {
        val pending = switchBack.pending() ?: return null
        if (pending.at > now()) {
            // Not due yet, e.g. an old alarm after the user scheduled a new one
            switchBack.rearm(pending)
            return null
        }
        switchBack.cancel()
        if (!settings.hasPermission() || isPrivate(settings.read().mode)) return null
        val setting = DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, pending.provider)
        try {
            settings.write(setting)
        } catch (e: SecurityException) {
            Log.w(TAG, "runDueSwitchBack: could not change settings", e)
            return null
        }
        Log.i(TAG, "switched back to ${pending.provider}")
        setting
    }

    /** The label of the saved server with this hostname, or the hostname itself */
    suspend fun displayName(provider: String): String =
        repository.getFirstByServer(provider)?.label?.ifBlank { null } ?: provider

    /**
     * Computes the next setting from the current one (outside the lock, it may query the
     * database) and applies it.
     */
    private suspend fun changeTo(
        fromOtherApp: Boolean,
        next: suspend (current: DnsSetting) -> DnsSetting?
    ): DnsChange {
        if (!settings.hasPermission()) return DnsChange.PermissionMissing
        val setting = next(settings.read()) ?: return DnsChange.NoEnabledServer
        return apply(setting, fromOtherApp)
    }

    private fun apply(next: DnsSetting, fromOtherApp: Boolean): DnsChange = permissionChecked {
        synchronized(lock) {
            val previous = settings.read()
            settings.write(next)
            val decision = AutoRevert.decide(
                previous, next, preferences.autoRevertEnabled, fromOtherApp
            )
            val switchBackAt = when (decision) {
                is AutoRevert.Decision.Schedule ->
                    scheduleLocked(decision.provider, preferences.autoRevertMinutes)

                AutoRevert.Decision.Cancel -> {
                    switchBack.cancel()
                    null
                }

                AutoRevert.Decision.Keep -> null
            }
            DnsChange.Applied(next, switchBackAt)
        }
    }

    /** Stores and arms a switch-back to [provider], returns its deadline. Call under [lock]. */
    private fun scheduleLocked(provider: String, minutes: Int): Long {
        val at = now() + minutes * 60_000L
        switchBack.schedule(PendingSwitchBack(at, provider))
        return at
    }

    /** Runs [change], reporting a revoked permission instead of throwing */
    private inline fun permissionChecked(change: () -> DnsChange): DnsChange = try {
        change()
    } catch (e: SecurityException) {
        Log.w(TAG, "could not change settings", e)
        DnsChange.PermissionMissing
    }

    private fun isPrivate(mode: String?) =
        mode.equals(PrivateDNSUtils.DNS_MODE_PRIVATE, ignoreCase = true)

    private companion object {
        const val TAG = "DnsController"
    }
}
