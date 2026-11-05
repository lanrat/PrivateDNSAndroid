package ru.karasevm.privatednstoggle.dns

import android.content.Context
import android.net.ConnectivityManager
import java.io.Closeable
import java.net.InetAddress

/**
 * Resolves Private DNS hostnames like Android does: with plain DNS through the current
 * network's DNS servers, bypassing Private DNS. Otherwise a broken or filtering active Private
 * DNS server would make every other server look broken too. Falls back to the system resolver
 * when that doesn't work, e.g. on a network that blocks plain DNS to its own servers.
 */
class NetworkDnsResolver(
    context: Context,
    private val timeoutMs: Int = 2_000,
) : HostResolver {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun resolve(hostname: String, track: (Closeable) -> Unit): List<InetAddress> {
        val dnsServers = runCatching {
            connectivityManager.getLinkProperties(connectivityManager.activeNetwork)?.dnsServers
        }.getOrNull().orEmpty()
        return PlainDns.lookup(hostname, dnsServers, timeoutMs, track)
            .ifEmpty { HostResolver.SYSTEM.resolve(hostname, track) }
    }
}
