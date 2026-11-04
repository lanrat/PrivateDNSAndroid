package ru.karasevm.privatednstoggle.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.karasevm.privatednstoggle.model.DnsServer

/**
 * In-memory [DnsServerDao] for unit tests, mirroring the SQL in the real DAO.
 */
class FakeDnsServerDao(initial: List<DnsServer> = emptyList()) : DnsServerDao {

    private val servers = MutableStateFlow<List<DnsServer>>(emptyList())
    private var nextId = 1

    init {
        initial.forEach { insertBlocking(it.server, it.label, it.enabled) }
    }

    /** Current rows, sorted like getAll() */
    val rows: List<DnsServer> get() = servers.value.sortedBy { it.sortOrder }

    private fun mutate(block: (List<DnsServer>) -> List<DnsServer>) {
        servers.value = block(servers.value)
    }

    override fun getAll(): Flow<List<DnsServer>> = servers

    override suspend fun getFirstEnabled(): DnsServer? = rows.firstOrNull { it.enabled }

    override suspend fun getFirstByServer(server: String): DnsServer? =
        servers.value.firstOrNull { it.server == server }

    override suspend fun getById(id: Int): DnsServer? = servers.value.firstOrNull { it.id == id }

    override suspend fun getNextEnabledByServer(server: String): DnsServer? {
        val current = getFirstByServer(server)?.sortOrder ?: return null
        return rows.firstOrNull { it.enabled && it.sortOrder!! > current }
    }

    override suspend fun deleteAll() = mutate { emptyList() }

    override suspend fun deleteById(id: Int) = mutate { list -> list.filter { it.id != id } }

    override suspend fun incrementSortOrder(startSortOrder: Int, endSortOrder: Int) = mutate { list ->
        list.map { if (it.sortOrder!! in startSortOrder..endSortOrder) it.copy(sortOrder = it.sortOrder + 1) else it }
    }

    override suspend fun decrementSortOrder(startSortOrder: Int, endSortOrder: Int) = mutate { list ->
        list.map { if (it.sortOrder!! in startSortOrder..endSortOrder) it.copy(sortOrder = it.sortOrder - 1) else it }
    }

    override suspend fun decrementSortOrderById(id: Int) {
        val order = getById(id)?.sortOrder ?: return
        mutate { list -> list.map { if (it.sortOrder!! > order) it.copy(sortOrder = it.sortOrder - 1) else it } }
    }

    override suspend fun updateLabel(id: Int, label: String) =
        update(id, null, label, null, null)

    override suspend fun updateServer(id: Int, server: String) =
        update(id, server, null, null, null)

    override suspend fun update(id: Int, server: String?, label: String?, sortOrder: Int?, enabled: Boolean?) =
        mutate { list ->
            list.map {
                if (it.id != id) it else it.copy(
                    server = server ?: it.server,
                    label = label ?: it.label,
                    sortOrder = sortOrder ?: it.sortOrder,
                    enabled = enabled ?: it.enabled
                )
            }
        }

    override suspend fun insert(server: String, label: String, enabled: Boolean) =
        insertBlocking(server, label, enabled)

    private fun insertBlocking(server: String, label: String, enabled: Boolean) = mutate { list ->
        val sortOrder = (list.maxOfOrNull { it.sortOrder!! } ?: -1) + 1
        list + DnsServer(nextId++, server, label, enabled, sortOrder)
    }
}
