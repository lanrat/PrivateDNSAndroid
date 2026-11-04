package ru.karasevm.privatednstoggle.util

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import ru.karasevm.privatednstoggle.data.DnsServerRepository

/**
 * A system Private DNS setting.
 *
 * @property mode one of [PrivateDNSUtils.DNS_MODE_OFF], [PrivateDNSUtils.DNS_MODE_AUTO] or
 * [PrivateDNSUtils.DNS_MODE_PRIVATE]
 * @property provider the Private DNS hostname. Android keeps it while the mode is off or auto,
 * so it can be restored later.
 */
data class DnsSetting(val mode: String, val provider: String?)

object PrivateDNSUtils {
    private const val TAG = "PrivateDNSUtils"

    const val DNS_MODE_OFF = "off"
    const val DNS_MODE_AUTO = "opportunistic"
    const val DNS_MODE_PRIVATE = "hostname"

    // What options to use when cycling
    const val AUTO_MODE_OPTION_OFF = 0
    const val AUTO_MODE_OPTION_AUTO = 1
    const val AUTO_MODE_OPTION_OFF_AUTO = 2
    const val AUTO_MODE_OPTION_PRIVATE = 3

    private const val PRIVATE_DNS_MODE = "private_dns_mode"
    private const val PRIVATE_DNS_PROVIDER = "private_dns_specifier"

    /** Content URI of the system dns mode setting, for observing changes */
    val PRIVATE_DNS_MODE_URI: Uri get() = Settings.Global.getUriFor(PRIVATE_DNS_MODE)

    /** Content URI of the system dns provider setting, for observing changes */
    val PRIVATE_DNS_PROVIDER_URI: Uri get() = Settings.Global.getUriFor(PRIVATE_DNS_PROVIDER)

    // Gets the system dns mode
    fun getPrivateMode(contentResolver: ContentResolver): String? {
        return Settings.Global.getString(contentResolver, PRIVATE_DNS_MODE)
    }

    // Gets the system dns provider
    fun getPrivateProvider(contentResolver: ContentResolver): String? {
        return Settings.Global.getString(contentResolver, PRIVATE_DNS_PROVIDER)
    }

    // Sets the system dns mode
    fun setPrivateMode(contentResolver: ContentResolver, value: String) {
        Settings.Global.putString(contentResolver, PRIVATE_DNS_MODE, value)
    }

    // Sets the system dns provider
    fun setPrivateProvider(contentResolver: ContentResolver, value: String?) {
        Settings.Global.putString(contentResolver, PRIVATE_DNS_PROVIDER, value)
    }

    /**
     * Writes [setting] to the system settings.
     *
     * The provider is only written for [DNS_MODE_PRIVATE], and before the mode, so the system
     * never applies private mode with a stale or empty hostname. For off and auto the stored
     * provider is left alone, the same as the system Settings app does.
     *
     * @param contentResolver content resolver
     * @param setting the setting to apply
     */
    fun applyDnsSetting(contentResolver: ContentResolver, setting: DnsSetting) {
        Log.d(TAG, "applyDnsSetting: $setting")
        if (setting.mode == DNS_MODE_PRIVATE) {
            setPrivateProvider(contentResolver, setting.provider)
        }
        setPrivateMode(contentResolver, setting.mode)
    }

    fun checkForPermission(context: Context): Boolean {
        return (checkSelfPermission(
            context, Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Gets private mode with the first enabled server, or null if there are no enabled servers.
     */
    private suspend fun firstPrivate(repository: DnsServerRepository): DnsSetting? =
        repository.getFirstEnabled()?.let { DnsSetting(DNS_MODE_PRIVATE, it.server) }

    /**
     * Gets the next dns mode while preserving the current dns provider.
     * Used by the "switch mode" shortcut.
     *
     * @param autoMode which modes to cycle through, one of the AUTO_MODE_OPTION_* constants
     * @param currentMode current system dns mode
     * @param currentProvider current system dns provider
     * @param repository dns server repository
     * @return the setting to apply, or null if there is nothing to switch to
     * (private mode is needed but no server is enabled)
     */
    suspend fun getNextMode(
        autoMode: Int,
        currentMode: String?,
        currentProvider: String?,
        repository: DnsServerRepository
    ): DnsSetting? {
        return when (currentMode?.lowercase()) {
            DNS_MODE_OFF, DNS_MODE_AUTO -> {
                if (currentProvider.isNullOrEmpty()) {
                    // no provider set, use regular logic
                    getNextProvider(autoMode, currentMode, currentProvider, repository)
                } else if (currentMode.equals(DNS_MODE_OFF, ignoreCase = true) &&
                    (autoMode == AUTO_MODE_OPTION_OFF_AUTO || autoMode == AUTO_MODE_OPTION_AUTO)
                ) {
                    // If system dns mode is off and auto is enabled switch to auto
                    DnsSetting(DNS_MODE_AUTO, currentProvider)
                } else {
                    // Otherwise switch to private, keeping the provider
                    DnsSetting(DNS_MODE_PRIVATE, currentProvider)
                }
            }

            // System dns mode is private (or unknown)
            else -> when (autoMode) {
                AUTO_MODE_OPTION_AUTO -> DnsSetting(DNS_MODE_AUTO, currentProvider)
                else -> DnsSetting(DNS_MODE_OFF, currentProvider)
            }
        }
    }

    /**
     * Gets the next dns provider from the database, taking into account the current
     * auto mode and private dns mode. Used by the tile and the "next server/mode" shortcut.
     *
     * @param autoMode which modes to cycle through, one of the AUTO_MODE_OPTION_* constants
     * @param currentMode current system dns mode
     * @param currentProvider current system dns provider
     * @param repository dns server repository
     * @return the setting to apply, or null if there is nothing to switch to
     * (private mode is needed but no server is enabled)
     */
    suspend fun getNextProvider(
        autoMode: Int,
        currentMode: String?,
        currentProvider: String?,
        repository: DnsServerRepository
    ): DnsSetting? {
        return when (currentMode?.lowercase()) {
            DNS_MODE_OFF -> {
                if (autoMode == AUTO_MODE_OPTION_AUTO || autoMode == AUTO_MODE_OPTION_OFF_AUTO) {
                    // if auto available set to auto preserving provider
                    DnsSetting(DNS_MODE_AUTO, currentProvider)
                } else {
                    // otherwise set to private with first provider
                    firstPrivate(repository)
                }
            }

            DNS_MODE_PRIVATE -> {
                val nextServer = if (currentProvider.isNullOrEmpty()) {
                    repository.getFirstEnabled()
                } else {
                    repository.getNextByServer(currentProvider)
                }
                when {
                    // set to private with next provider
                    nextServer != null -> DnsSetting(DNS_MODE_PRIVATE, nextServer.server)
                    // no more providers, wrap around to the first one
                    autoMode == AUTO_MODE_OPTION_PRIVATE -> firstPrivate(repository)
                    // otherwise set to auto or off
                    autoMode == AUTO_MODE_OPTION_AUTO -> DnsSetting(DNS_MODE_AUTO, currentProvider)
                    else -> DnsSetting(DNS_MODE_OFF, currentProvider)
                }
            }

            // system dns mode is auto or unknown, set to private with first provider
            else -> firstPrivate(repository)
        }
    }
}
