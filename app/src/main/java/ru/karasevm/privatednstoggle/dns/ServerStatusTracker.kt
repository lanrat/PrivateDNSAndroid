package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Whether a server works, as shown in the server list */
sealed interface ServerStatus {
    data object Testing : ServerStatus

    /** Tested at [at] (ms since the epoch) */
    data class Tested(val result: ProbeResult, val at: Long) : ServerStatus
}

/**
 * Tests servers with [probe] in [scope] and keeps the latest status of each. Results are only
 * kept in memory, i.e. while the app is open. Host names are case-insensitive, so the
 * statuses are keyed by [key].
 */
class ServerStatusTracker(
    private val probe: ServerProbe,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    maxConcurrentTests: Int = 4,
) {
    private val _statuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())

    /** The status of each tested server, keyed by [key] */
    val statuses: StateFlow<Map<String, ServerStatus>> = _statuses.asStateFlow()

    private val running = ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(maxConcurrentTests)

    /**
     * Tests [servers] that are not being tested and have no result newer than [maxAgeMs].
     * Pass 0 to test them all again.
     */
    fun test(servers: Collection<String>, maxAgeMs: Long = 0) {
        val now = now()
        for (server in servers.map(::key).distinct()) {
            if (running[server]?.isActive == true) continue
            val status = _statuses.value[server]
            if (status is ServerStatus.Tested && maxAgeMs > 0 && now - status.at < maxAgeMs) continue
            set(server, ServerStatus.Testing)
            running[server] = scope.launch {
                val result = try {
                    permits.withPermit { probe.probe(server) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A probe shouldn't throw, but an exception here would crash the app
                    ProbeResult.Failed(ProbeResult.Reason.OTHER, e.toString())
                }
                set(server, ServerStatus.Tested(result, now()))
            }
        }
    }

    private fun set(server: String, status: ServerStatus) =
        _statuses.update { it + (server to status) }

    companion object {
        /** The key of [server] in [statuses] */
        fun key(server: String): String = server.trim().lowercase()
    }
}
