package ru.karasevm.privatednstoggle.data

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.AppPreferences
import ru.karasevm.privatednstoggle.util.ServerValidation

/**
 * Exports the servers and options as JSON, and imports them, also from the format of
 * versions before the server list moved to Room.
 */
class BackupManager(
    private val repository: DnsServerRepository,
    private val preferences: AppPreferences,
) {
    @Serializable
    data class Backup(
        @SerialName("dns_servers") val dnsServers: List<DnsServer>,
        @SerialName("auto_mode") val autoMode: Int?,
        @SerialName("require_unlock") val requireUnlock: Boolean?,
    )

    /** The format before the server list moved to Room: "label : server" entries, comma separated */
    @Serializable
    data class LegacyBackup(
        @SerialName("dns_servers") val dnsServers: String,
        @SerialName("auto_mode") val autoMode: Int?,
        @SerialName("require_unlock") val requireUnlock: Boolean?,
    )

    /** Exports the saved servers (from the database) and the options as JSON */
    suspend fun export(): String = Json.encodeToString(
        Backup(repository.allServers.first(), preferences.autoMode, preferences.requireUnlock)
    )

    /**
     * Imports a backup made by [export] or by an older version. Servers that are already
     * saved are skipped, so importing twice doesn't duplicate them.
     *
     * @return false if [json] is not a backup, or it has servers but none is valid
     */
    suspend fun import(json: String): Boolean {
        val (servers, autoMode, requireUnlock) = parse(json) ?: return false
        val valid = validServers(servers)
        if (valid.isEmpty() && servers.isNotEmpty()) {
            Log.w(TAG, "import: none of the ${servers.size} servers is valid")
            return false
        }
        repository.insertAllIfAbsent(valid)
        autoMode?.let { preferences.autoMode = it }
        requireUnlock?.let { preferences.requireUnlock = it }
        return true
    }

    private data class Parsed(val servers: List<DnsServer>, val autoMode: Int?, val requireUnlock: Boolean?)

    private fun parse(json: String): Parsed? {
        runCatching { jsonParser.decodeFromString<Backup>(json) }
            .onSuccess { return Parsed(it.dnsServers, it.autoMode, it.requireUnlock) }
            .onFailure { Log.i(TAG, "parse: not a current backup, trying the legacy format", it) }
        runCatching { jsonParser.decodeFromString<LegacyBackup>(json) }
            .onSuccess {
                return Parsed(
                    parseLegacyServers(it.dnsServers.split(",")),
                    // Older versions wrote both options
                    it.autoMode ?: 0,
                    it.requireUnlock == true
                )
            }
            .onFailure { Log.w(TAG, "parse: not a backup", it) }
        return null
    }

    companion object {
        private const val TAG = "BackupManager"

        // Backups from newer versions may have more fields
        private val jsonParser = Json { ignoreUnknownKeys = true }

        /** Parses legacy "label : server" or "server" entries */
        fun parseLegacyServers(entries: List<String>): List<DnsServer> = entries.map { entry ->
            val parts = entry.split(" : ")
            if (parts.size == 2) DnsServer(0, parts[1], parts[0]) else DnsServer(0, entry, "")
        }

        /**
         * Trims the addresses and drops servers that can't be used as a Private DNS hostname,
         * e.g. URLs or IP addresses in a hand-edited backup, which would otherwise be written
         * to the system setting and break DNS.
         */
        fun validServers(servers: List<DnsServer>): List<DnsServer> = servers
            .map { it.copy(server = it.server.trim(), label = it.label.trim()) }
            .filter { ServerValidation.isValidHostname(it.server) }
    }
}
