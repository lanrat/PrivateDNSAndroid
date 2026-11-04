package ru.karasevm.privatednstoggle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.karasevm.privatednstoggle.model.DnsServer

class BackupUtilsTest {

    @Test
    fun validServers_trimsAndDropsWhatCantBeAPrivateDnsHostname() {
        val imported = listOf(
            DnsServer(server = " dns.example ", label = " Example "),
            DnsServer(server = "1.1.1.1"),
            DnsServer(server = "https://dns.example/dns-query"),
            DnsServer(server = ""),
            DnsServer(server = "dns.google", enabled = false),
        )
        assertEquals(
            listOf(
                DnsServer(server = "dns.example", label = "Example"),
                DnsServer(server = "dns.google", enabled = false),
            ),
            BackupUtils.validServers(imported)
        )
    }

    @Test
    fun isValidHostname() {
        assertTrue(ServerValidation.isValidHostname("dns.adguard-dns.com"))
        assertTrue(ServerValidation.isValidHostname("abc123.dns.nextdns.io"))
        for (invalid in listOf("", " dns.google", "dns.google ", "1.1.1.1", "dns..google", "https://dns.google")) {
            assertFalse(invalid, ServerValidation.isValidHostname(invalid))
        }
    }
}
