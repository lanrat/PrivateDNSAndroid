package ru.karasevm.privatednstoggle.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.model.DnsServer

class DnsServerViewModel(private val dnsServerRepository: DnsServerRepository) : ViewModel() {

    val allServers: LiveData<List<DnsServer>> = dnsServerRepository.allServers.asLiveData()

    fun getAll() = dnsServerRepository.getAll()

    /** All servers as currently saved */
    suspend fun currentServers(): List<DnsServer> = dnsServerRepository.allServers.first()

    suspend fun getById(id: Int) = dnsServerRepository.getById(id)

    fun insert(dnsServer: DnsServer) =
        viewModelScope.launch {
            dnsServerRepository.insert(dnsServer)
        }

    /** Adds the servers that are not saved yet, skipping duplicates */
    fun insertAllIfAbsent(dnsServers: List<DnsServer>) =
        viewModelScope.launch {
            dnsServerRepository.insertAllIfAbsent(dnsServers)
        }

    /**
     * Adds [dnsServer] unless its address is already saved, in which case [onDuplicate] is
     * called on the main thread instead.
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
     * which case [onDuplicate] is called on the main thread instead. [onUpdated] gets the
     * server as it was before the update.
     */
    fun updateIfUnique(
        id: Int,
        server: String,
        label: String?,
        enabled: Boolean,
        onDuplicate: () -> Unit,
        onUpdated: (old: DnsServer?) -> Unit
    ) = viewModelScope.launch {
        val existing = dnsServerRepository.getFirstByServer(server)
        if (existing != null && existing.id != id) {
            onDuplicate()
            return@launch
        }
        val old = dnsServerRepository.getById(id)
        when (dnsServerRepository.update(id, server, label, null, enabled)) {
            1 -> onUpdated(old)
            // Ignored by the unique index: another server took the address in the meantime
            else -> if (old != null) onDuplicate()
        }
    }

    fun update(
        id: Int,
        server: String? = null,
        label: String? = null,
        sortOrder: Int? = null,
        enabled: Boolean? = null
    ) = viewModelScope.launch { dnsServerRepository.update(id, server, label, sortOrder, enabled) }

    fun move(sortOrder: Int, newSortOrder: Int, id: Int) =
        viewModelScope.launch { dnsServerRepository.move(sortOrder, newSortOrder, id) }

    fun delete(id: Int) = viewModelScope.launch { dnsServerRepository.delete(id) }

}

class DnsServerViewModelFactory(private val dnsServerRepository: DnsServerRepository) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DnsServerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DnsServerViewModel(dnsServerRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}