package ru.karasevm.privatednstoggle.util

import android.content.SharedPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.karasevm.privatednstoggle.data.DnsServerViewModel
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock

object BackupUtils {
    @Serializable
    data class Backup(
        @SerialName("dns_servers") val dnsServers: List<DnsServer>,
        @SerialName("auto_mode") val autoMode: Int?,
        @SerialName("require_unlock") val requireUnlock: Boolean?,
    )

    @Serializable
    data class LegacyBackup(
        @SerialName("dns_servers") val dnsServers: String,
        @SerialName("auto_mode") val autoMode: Int?,
        @SerialName("require_unlock") val requireUnlock: Boolean?,
    )

    /**
     *  Exports all the preferences
     *  @param viewModel View model
     *  @param sharedPreferences Shared preferences
     */
    suspend fun export(viewModel: DnsServerViewModel, sharedPreferences: SharedPreferences): Backup {
        return Backup(
            // From the database: the LiveData may not have loaded yet, e.g. when the activity
            // was recreated while the file picker was open, which exported an empty list
            viewModel.currentServers(),
            sharedPreferences.autoMode,
            sharedPreferences.requireUnlock
        )
    }

    /**
     *  Imports all the preferences
     *  @param backup Deserialized backup
     *  @param viewModel View model
     */
    fun import(
        backup: Backup,
        viewModel: DnsServerViewModel,
        sharedPreferences: SharedPreferences
    ) {
        // Servers that are already saved are skipped, so importing twice doesn't duplicate them
        val servers = validServers(backup.dnsServers)
        require(servers.isNotEmpty() || backup.dnsServers.isEmpty()) { "No valid servers in the backup" }
        viewModel.insertAllIfAbsent(servers)
        sharedPreferences.autoMode = backup.autoMode ?: sharedPreferences.autoMode
        sharedPreferences.requireUnlock = backup.requireUnlock ?: sharedPreferences.requireUnlock
    }

    /**
     *  Imports old server list
     *  @param legacyBackup Deserialized backup
     *  @param viewModel View model
     *  @param sharedPreferences Shared preferences
     */
    fun importLegacy(
        legacyBackup: LegacyBackup,
        viewModel: DnsServerViewModel,
        sharedPreferences: SharedPreferences
    ) {
        val servers = legacyBackup.dnsServers.split(",").map { server ->
            val parts = server.split(" : ")
            if (parts.size == 2) {
                DnsServer(0, parts[1], parts[0])
            } else {
                DnsServer(0, server, "")
            }
        }
        val valid = validServers(servers)
        require(valid.isNotEmpty()) { "No valid servers in the backup" }
        viewModel.insertAllIfAbsent(valid)
        sharedPreferences.autoMode = legacyBackup.autoMode?: 0
        sharedPreferences.requireUnlock = legacyBackup.requireUnlock == true
    }

    /**
     * Trims the addresses and drops servers that can't be used as a Private DNS hostname,
     * e.g. URLs or IP addresses in a hand-edited backup, which would otherwise be written to
     * the system setting and break DNS.
     */
    fun validServers(servers: List<DnsServer>): List<DnsServer> = servers
        .map { it.copy(server = it.server.trim(), label = it.label.trim()) }
        .filter { ServerValidation.isValidHostname(it.server) }
}
