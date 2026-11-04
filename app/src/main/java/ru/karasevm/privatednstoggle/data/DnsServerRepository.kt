package ru.karasevm.privatednstoggle.data

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import ru.karasevm.privatednstoggle.model.DnsServer

class DnsServerRepository(private val dnsServerDao: DnsServerDao) {

    val allServers: Flow<List<DnsServer>> = dnsServerDao.getAll()

    @WorkerThread
    fun getAll() = dnsServerDao.getAll()

    @WorkerThread
    suspend fun getFirstEnabled() = dnsServerDao.getFirstEnabled()

    @WorkerThread
    suspend fun getById(id: Int) = dnsServerDao.getById(id)

    @WorkerThread
    suspend fun getFirstByServer(server: String) = dnsServerDao.getFirstByServer(server)

    @WorkerThread
    suspend fun getNextByServer(server: String) = dnsServerDao.getNextEnabledByServer(server)

    @WorkerThread
    suspend fun insert(dnsServer: DnsServer) {
        dnsServerDao.insert(dnsServer.server, dnsServer.label, dnsServer.enabled)
    }

    /**
     * Inserts the servers that are not in the database yet, e.g. when importing a backup
     */
    @WorkerThread
    suspend fun insertAllIfAbsent(dnsServers: List<DnsServer>) {
        dnsServerDao.insertAllIfAbsent(dnsServers)
    }

    /** @return the number of updated rows, 0 if the server is gone or the address is taken */
    @WorkerThread
    suspend fun update(
        id: Int,
        server: String?,
        label: String?,
        sortOrder: Int?,
        enabled: Boolean?
    ): Int = dnsServerDao.update(id, server, label, sortOrder, enabled)

    @WorkerThread
    suspend fun move(sortOrder: Int, newSortOrder: Int, id: Int) {
        if (sortOrder == newSortOrder) {
            return
        }
        if (newSortOrder > sortOrder) {
            dnsServerDao.moveDown(sortOrder, newSortOrder, id)
        } else {
            dnsServerDao.moveUp(sortOrder, newSortOrder, id)
        }
    }

    @WorkerThread
    suspend fun delete(id: Int) {
        dnsServerDao.deleteAndDecrement(id)
    }


}