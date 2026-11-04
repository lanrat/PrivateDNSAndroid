package ru.karasevm.privatednstoggle.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.karasevm.privatednstoggle.FakeAppPreferences
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils
import ru.karasevm.privatednstoggle.util.ServerValidation

class BackupManagerTest {

    private fun servers(dao: FakeDnsServerDao) = dao.rows.map { Triple(it.server, it.label, it.enabled) }

    @Test
    fun exportAndImportRoundTrip() = runTest {
        val source = FakeDnsServerDao(
            listOf(DnsServer(server = "a.example", label = "A"), DnsServer(server = "b.example", enabled = false))
        )
        val sourcePrefs = FakeAppPreferences(autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_AUTO, requireUnlock = true)
        val json = BackupManager(DnsServerRepository(source), sourcePrefs).export()

        val target = FakeDnsServerDao(listOf(DnsServer(server = "a.example", label = "kept")))
        val targetPrefs = FakeAppPreferences()
        assertTrue(BackupManager(DnsServerRepository(target), targetPrefs).import(json))

        // a.example was already saved and is skipped
        assertEquals(listOf(Triple("a.example", "kept", true), Triple("b.example", "", false)), servers(target))
        assertEquals(PrivateDNSUtils.AUTO_MODE_OPTION_AUTO, targetPrefs.autoMode)
        assertTrue(targetPrefs.requireUnlock)
    }

    @Test
    fun importsTheLegacyFormat() = runTest {
        val dao = FakeDnsServerDao()
        val prefs = FakeAppPreferences(autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_AUTO)
        val json = """{"dns_servers":"Google : dns.google,one.one.one.one","auto_mode":null,"require_unlock":null}"""

        assertTrue(BackupManager(DnsServerRepository(dao), prefs).import(json))

        assertEquals(listOf(Triple("dns.google", "Google", true), Triple("one.one.one.one", "", true)), servers(dao))
        // older versions always wrote the options, missing ones reset to the defaults
        assertEquals(PrivateDNSUtils.AUTO_MODE_OPTION_OFF, prefs.autoMode)
    }

    @Test
    fun ignoresUnknownFieldsFromNewerVersions() = runTest {
        val dao = FakeDnsServerDao()
        val json = """{"dns_servers":[{"server":"a.example","future":1}],"auto_mode":1,"require_unlock":false,"future":true}"""
        assertTrue(BackupManager(DnsServerRepository(dao), FakeAppPreferences()).import(json))
        assertEquals(1, dao.getAll().first().size)
    }

    @Test
    fun rejectsInvalidInput() = runTest {
        val dao = FakeDnsServerDao()
        val manager = BackupManager(DnsServerRepository(dao), FakeAppPreferences())
        assertFalse(manager.import("not json"))
        assertFalse(manager.import("""{"something":"else"}"""))
        // servers, but none usable
        assertFalse(manager.import("""{"dns_servers":[{"server":"1.1.1.1"}],"auto_mode":null,"require_unlock":null}"""))
        assertEquals(0, dao.rows.size)
    }

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
            BackupManager.validServers(imported)
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
