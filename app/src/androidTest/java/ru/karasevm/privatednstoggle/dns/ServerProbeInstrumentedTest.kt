package ru.karasevm.privatednstoggle.dns

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.Device

/**
 * The probe on a real Android network stack against public DNS-over-TLS servers, with
 * Android's own trust store and hostname verifier. Needs internet access with port 853 open.
 */
@RunWith(AndroidJUnit4::class)
class ServerProbeInstrumentedTest {

    private val probe = DotServerProbe(NetworkDnsResolver(Device.context))

    private fun probe(hostname: String) = runBlocking { probe.probe(hostname) }

    @Test
    fun publicServersWork() {
        for (server in listOf("dns.google", "one.one.one.one", "dns.google.")) {
            val result = probe(server)
            assertTrue("$server: $result", result is ProbeResult.Working)
        }
    }

    @Test
    fun unknownNameIsReported() {
        assertEquals(ProbeResult.Reason.HOSTNAME_NOT_FOUND, (probe("does-not-exist.invalid") as ProbeResult.Failed).reason)
    }

    @Test
    fun hostWithoutDnsOverTlsFails() {
        // example.com resolves but doesn't serve DNS-over-TLS on port 853
        val result = probe("example.com")
        assertTrue(result.toString(), result is ProbeResult.Failed && result.reason != ProbeResult.Reason.HOSTNAME_NOT_FOUND)
    }

    @Test
    fun namesAreResolvedWithPlainDnsThroughTheNetwork() {
        val connectivityManager = Device.context.getSystemService(ConnectivityManager::class.java)
        val dnsServers = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)!!.dnsServers
        assertTrue("no DNS servers on the network", dnsServers.isNotEmpty())
        val addresses = PlainDns.lookup("dns.google", dnsServers, 2_000, {})
        assertTrue("plain DNS lookup failed", addresses.isNotEmpty())
    }
}
