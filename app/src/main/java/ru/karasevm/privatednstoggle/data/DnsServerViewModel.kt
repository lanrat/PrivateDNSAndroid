package ru.karasevm.privatednstoggle.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.dns.DnsController
import ru.karasevm.privatednstoggle.dns.ServerProbe
import ru.karasevm.privatednstoggle.dns.ServerStatus
import ru.karasevm.privatednstoggle.dns.ServerStatusTracker
import ru.karasevm.privatednstoggle.model.DnsServer

/**
 * The server list and backups for the app's screens. Work started here finishes even if the
 * screen is recreated meanwhile; callbacks run on the main thread.
 */
class DnsServerViewModel(
    private val dnsServerRepository: DnsServerRepository,
    private val backupManager: BackupManager,
    private val dnsController: DnsController,
    serverProbe: ServerProbe,
) : ViewModel() {

    val allServers: LiveData<List<DnsServer>> = dnsServerRepository.allServers.asLiveData()

    private val statusTracker = ServerStatusTracker(serverProbe, viewModelScope)

    /** Whether each server works, keyed by [ServerStatusTracker.key] */
    val serverStatuses: StateFlow<Map<String, ServerStatus>> = statusTracker.statuses

    /**
     * Tests whether [servers] work. Skips servers with a result newer than [maxAgeMs];
     * 0 tests them all again.
     */
    fun testServers(servers: Collection<String>, maxAgeMs: Long = 0) =
        statusTracker.test(servers, maxAgeMs)

    fun getAll() = dnsServerRepository.getAll()

    /**
     * Adds [dnsServer] unless its address is already saved, in which case [onDuplicate] is
     * called instead.
     */
    fun insertIfAbsent(dnsServer: DnsServer, onDuplicate: () -> Unit) =
        viewModelScope.launch {
            if (dnsServerRepository.getFirstByServer(dnsServer.server) != null) {
                onDuplicate()
            } else {
                dnsServerRepository.insert(dnsServer)
            }
        }

    /**
     * Updates the server with [id], unless another saved server already uses [server], in
     * which case [onDuplicate] is called instead.
     */
    fun updateIfUnique(
        id: Int,
        server: String,
        label: String?,
        enabled: Boolean,
        onDuplicate: () -> Unit,
    ) = viewModelScope.launch {
        val existing = dnsServerRepository.getFirstByServer(server)
        if (existing != null && existing.id != id) {
            onDuplicate()
            return@launch
        }
        val old = dnsServerRepository.getById(id)
        when (dnsServerRepository.update(id, server, label, null, enabled)) {
            1 -> if (old != null && (!old.server.equals(server, ignoreCase = true) || !enabled)) {
                // Don't switch back to a server that was disabled or changed
                dnsController.cancelSwitchBackTo(old.server)
            }
            // Ignored by the unique index: another server took the address in the meantime
            else -> if (old != null) onDuplicate()
        }
    }

    fun move(sortOrder: Int, newSortOrder: Int, id: Int) =
        viewModelScope.launch { dnsServerRepository.move(sortOrder, newSortOrder, id) }

    fun delete(id: Int) = viewModelScope.launch {
        // Don't switch back to a server that no longer exists
        dnsServerRepository.getById(id)?.let { dnsController.cancelSwitchBackTo(it.server) }
        dnsServerRepository.delete(id)
    }

    /** The servers and options as JSON, see [BackupManager.export] */
    suspend fun exportJson(): String = backupManager.export()

    /** Imports a backup, see [BackupManager.import]; [onResult] gets whether it worked */
    fun importJson(json: String, onResult: (success: Boolean) -> Unit) = viewModelScope.launch {
        onResult(backupManager.import(json))
    }
}

class DnsServerViewModelFactory(private val app: PrivateDNSApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DnsServerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DnsServerViewModel(app.repository, app.backupManager, app.dnsController, app.serverProbe) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
