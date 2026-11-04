package ru.karasevm.privatednstoggle.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.data.FakeDnsServerDao
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.AUTO_MODE_OPTION_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE
import ru.karasevm.privatednstoggle.util.ShortcutActions.ACTION_DO_CYCLE
import ru.karasevm.privatednstoggle.util.ShortcutActions.ACTION_SWITCH_MODE
import ru.karasevm.privatednstoggle.util.ShortcutActions.ACTION_TURN_OFF
import ru.karasevm.privatednstoggle.util.ShortcutActions.ACTION_TURN_ON

class ShortcutActionsTest {

    // a and c are enabled, b is disabled
    private fun repository() = DnsServerRepository(
        FakeDnsServerDao(
            listOf(
                DnsServer(server = "a.example"),
                DnsServer(server = "b.example", enabled = false),
                DnsServer(server = "c.example"),
            )
        )
    )

    private suspend fun next(action: String, mode: String?, provider: String?, repository: DnsServerRepository = repository()) =
        ShortcutActions.nextSetting(action, AUTO_MODE_OPTION_OFF, mode, provider, repository)

    @Test
    fun cycleAndSwitchModeUseTheTileLogic() = runTest {
        assertEquals(DnsSetting(DNS_MODE_PRIVATE, "c.example"), next(ACTION_DO_CYCLE, DNS_MODE_PRIVATE, "a.example"))
        assertEquals(DnsSetting(DNS_MODE_OFF, "a.example"), next(ACTION_SWITCH_MODE, DNS_MODE_PRIVATE, "a.example"))
    }

    @Test
    fun turnOnKeepsCurrentServerIfSavedAndEnabled() = runTest {
        assertEquals(DnsSetting(DNS_MODE_PRIVATE, "c.example"), next(ACTION_TURN_ON, DNS_MODE_OFF, "c.example"))
    }

    @Test
    fun turnOnUsesFirstEnabledServerOtherwise() = runTest {
        // disabled, unknown and missing current server
        for (provider in listOf("b.example", "other.example", null, "")) {
            assertEquals(
                "provider $provider",
                DnsSetting(DNS_MODE_PRIVATE, "a.example"),
                next(ACTION_TURN_ON, DNS_MODE_AUTO, provider)
            )
        }
        assertNull(next(ACTION_TURN_ON, DNS_MODE_OFF, null, DnsServerRepository(FakeDnsServerDao())))
    }

    @Test
    fun turnOffKeepsTheServer() = runTest {
        assertEquals(DnsSetting(DNS_MODE_OFF, "c.example"), next(ACTION_TURN_OFF, DNS_MODE_PRIVATE, "c.example"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownActionIsRejected() = runTest {
        next("privatednstoggle://something", DNS_MODE_OFF, null)
    }
}
