package ru.karasevm.privatednstoggle.util

import ru.karasevm.privatednstoggle.data.DnsServerRepository

/**
 * The actions behind the app's shortcuts. The action is the shortcut intent's data URI.
 */
object ShortcutActions {
    const val ACTION_DO_CYCLE = "privatednstoggle://do_cycle" // regular cycle
    const val ACTION_SWITCH_MODE = "privatednstoggle://switch_mode" // toggle private and non-private modes
    const val ACTION_TURN_ON = "privatednstoggle://turn_on"
    const val ACTION_TURN_OFF = "privatednstoggle://turn_off"

    /** All supported actions */
    val ACTIONS = listOf(ACTION_DO_CYCLE, ACTION_SWITCH_MODE, ACTION_TURN_ON, ACTION_TURN_OFF)

    /**
     * Gets the setting [action] switches to.
     *
     * @param action one of the ACTION_* constants
     * @param autoMode which modes to cycle through, one of the AUTO_MODE_OPTION_* constants
     * @param currentMode current system dns mode
     * @param currentProvider current system dns provider
     * @param repository dns server repository
     * @return the setting to apply, or null if private mode is needed but no server is enabled
     * @throws IllegalArgumentException for an unknown action
     */
    suspend fun nextSetting(
        action: String,
        autoMode: Int,
        currentMode: String?,
        currentProvider: String?,
        repository: DnsServerRepository
    ): DnsSetting? = when (action) {
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

        else -> throw IllegalArgumentException("Unknown action $action")
    }
}
