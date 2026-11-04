package ru.karasevm.privatednstoggle.data

import android.content.SharedPreferences
import android.util.Log
import ru.karasevm.privatednstoggle.util.PreferenceHelper.dns_servers

/**
 * Moves the server list of versions before 1.9, which was kept in shared preferences, to the
 * database. Runs at app start; does nothing once the list is moved.
 */
object LegacyServerMigration {
    private const val TAG = "LegacyServerMigration"

    suspend fun run(preferences: SharedPreferences, repository: DnsServerRepository) {
        val entries = preferences.dns_servers
        if (entries.isEmpty() || entries[0] == "") return
        Log.i(TAG, "migrating ${entries.size} servers")
        val servers = BackupManager.validServers(BackupManager.parseLegacyServers(entries))
        // A single transaction keeps the original order
        repository.insertAllIfAbsent(servers)
        preferences.dns_servers = mutableListOf()
    }
}
