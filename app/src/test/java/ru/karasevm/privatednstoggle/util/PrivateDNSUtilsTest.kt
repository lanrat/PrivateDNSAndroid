package ru.karasevm.privatednstoggle.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.data.FakeDnsServerDao
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.AUTO_MODE_OPTION_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.AUTO_MODE_OPTION_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.AUTO_MODE_OPTION_OFF_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.AUTO_MODE_OPTION_PRIVATE
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE

class PrivateDNSUtilsTest {

    // a and c are enabled, b is disabled
    private fun repository() = DnsServerRepository(
        FakeDnsServerDao(
            listOf(
                DnsServer(server = "a.example", label = "A"),
                DnsServer(server = "b.example", enabled = false),
                DnsServer(server = "c.example"),
            )
        )
    )

    private fun emptyRepository() = DnsServerRepository(FakeDnsServerDao())

    private fun private(server: String) = DnsSetting(DNS_MODE_PRIVATE, server)

    // getNextProvider (tile and "next server/mode" shortcut)

    @Test
    fun nextProvider_offGoesToFirstServerWhenAutoIsNotCycled() = runTest {
        for (autoMode in listOf(AUTO_MODE_OPTION_OFF, AUTO_MODE_OPTION_PRIVATE)) {
            assertEquals(
                private("a.example"),
                PrivateDNSUtils.getNextProvider(autoMode, DNS_MODE_OFF, "c.example", repository())
            )
        }
    }

    @Test
    fun nextProvider_offGoesToAutoKeepingProvider() = runTest {
        for (autoMode in listOf(AUTO_MODE_OPTION_AUTO, AUTO_MODE_OPTION_OFF_AUTO)) {
            assertEquals(
                DnsSetting(DNS_MODE_AUTO, "c.example"),
                PrivateDNSUtils.getNextProvider(autoMode, DNS_MODE_OFF, "c.example", repository())
            )
        }
    }

    @Test
    fun nextProvider_autoUnsetOrUnknownModeGoesToFirstServer() = runTest {
        for (mode in listOf(DNS_MODE_AUTO, null, "something-new")) {
            assertEquals(
                private("a.example"),
                PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF_AUTO, mode, null, repository())
            )
        }
    }

    @Test
    fun nextProvider_privateGoesToNextEnabledServer() = runTest {
        // b.example is disabled and skipped
        assertEquals(
            private("c.example"),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_PRIVATE, "a.example", repository())
        )
        // a disabled current server still has a position in the list
        assertEquals(
            private("c.example"),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_PRIVATE, "b.example", repository())
        )
    }

    @Test
    fun nextProvider_modeIsCaseInsensitive() = runTest {
        assertEquals(
            private("c.example"),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, "HOSTNAME", "a.example", repository())
        )
    }

    @Test
    fun nextProvider_afterLastServer() = runTest {
        val expected = mapOf(
            AUTO_MODE_OPTION_OFF to DnsSetting(DNS_MODE_OFF, "c.example"),
            AUTO_MODE_OPTION_AUTO to DnsSetting(DNS_MODE_AUTO, "c.example"),
            AUTO_MODE_OPTION_OFF_AUTO to DnsSetting(DNS_MODE_OFF, "c.example"),
            AUTO_MODE_OPTION_PRIVATE to private("a.example"),
        )
        for ((autoMode, setting) in expected) {
            assertEquals(
                "autoMode $autoMode",
                setting,
                PrivateDNSUtils.getNextProvider(autoMode, DNS_MODE_PRIVATE, "c.example", repository())
            )
        }
    }

    @Test
    fun nextProvider_privateWithUnknownOrEmptyProvider() = runTest {
        // A server set outside the app is treated as the end of the list
        assertEquals(
            private("a.example"),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_PRIVATE, DNS_MODE_PRIVATE, "other.example", repository())
        )
        assertEquals(
            DnsSetting(DNS_MODE_OFF, "other.example"),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_PRIVATE, "other.example", repository())
        )
        for (provider in listOf(null, "")) {
            assertEquals(
                private("a.example"),
                PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_PRIVATE, provider, repository())
            )
        }
    }

    @Test
    fun nextProvider_returnsNullWithoutEnabledServers() = runTest {
        // Used to crash with a NullPointerException in "private only" mode
        assertNull(
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_PRIVATE, DNS_MODE_PRIVATE, "x.example", emptyRepository())
        )
        assertNull(PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_OFF, null, emptyRepository()))
        assertNull(PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_OFF, DNS_MODE_AUTO, null, emptyRepository()))
        // Switching to off or auto still works
        assertEquals(
            DnsSetting(DNS_MODE_AUTO, null),
            PrivateDNSUtils.getNextProvider(AUTO_MODE_OPTION_AUTO, DNS_MODE_OFF, null, emptyRepository())
        )
    }

    // getNextMode ("switch mode" shortcut)

    @Test
    fun nextMode_offOrAutoWithProviderGoesToPrivateWithSameProvider() = runTest {
        assertEquals(
            private("c.example"),
            PrivateDNSUtils.getNextMode(AUTO_MODE_OPTION_OFF, DNS_MODE_OFF, "c.example", repository())
        )
        assertEquals(
            private("c.example"),
            PrivateDNSUtils.getNextMode(AUTO_MODE_OPTION_OFF_AUTO, DNS_MODE_AUTO, "c.example", repository())
        )
        // Even if the provider is not a saved server
        assertEquals(
            private("other.example"),
            PrivateDNSUtils.getNextMode(AUTO_MODE_OPTION_OFF, DNS_MODE_OFF, "other.example", repository())
        )
    }

    @Test
    fun nextMode_offGoesToAutoWhenAutoIsCycled() = runTest {
        for (autoMode in listOf(AUTO_MODE_OPTION_AUTO, AUTO_MODE_OPTION_OFF_AUTO)) {
            assertEquals(
                DnsSetting(DNS_MODE_AUTO, "c.example"),
                PrivateDNSUtils.getNextMode(autoMode, DNS_MODE_OFF, "c.example", repository())
            )
        }
    }

    @Test
    fun nextMode_withoutProviderFallsBackToNextProvider() = runTest {
        for (provider in listOf(null, "")) {
            assertEquals(
                private("a.example"),
                PrivateDNSUtils.getNextMode(AUTO_MODE_OPTION_OFF, DNS_MODE_OFF, provider, repository())
            )
        }
        assertNull(PrivateDNSUtils.getNextMode(AUTO_MODE_OPTION_OFF, DNS_MODE_OFF, null, emptyRepository()))
    }

    @Test
    fun nextMode_privateGoesToOffOrAutoKeepingProvider() = runTest {
        val expected = mapOf(
            AUTO_MODE_OPTION_OFF to DNS_MODE_OFF,
            AUTO_MODE_OPTION_AUTO to DNS_MODE_AUTO,
            AUTO_MODE_OPTION_OFF_AUTO to DNS_MODE_OFF,
            AUTO_MODE_OPTION_PRIVATE to DNS_MODE_OFF,
        )
        for ((autoMode, mode) in expected) {
            assertEquals(
                "autoMode $autoMode",
                DnsSetting(mode, "c.example"),
                PrivateDNSUtils.getNextMode(autoMode, DNS_MODE_PRIVATE, "c.example", repository())
            )
        }
    }
}
