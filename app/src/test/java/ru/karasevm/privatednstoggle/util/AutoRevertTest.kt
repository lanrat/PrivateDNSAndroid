package ru.karasevm.privatednstoggle.util

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.karasevm.privatednstoggle.util.AutoRevert.Decision
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE

class AutoRevertTest {

    private val private = DnsSetting(DNS_MODE_PRIVATE, "a.example")
    private val off = DnsSetting(DNS_MODE_OFF, "a.example")
    private val auto = DnsSetting(DNS_MODE_AUTO, "a.example")

    @Test
    fun turningOffOrAutoFromPrivateSchedulesSwitchBackToPreviousServer() {
        for (next in listOf(off, auto)) {
            assertEquals(
                Decision.Schedule("a.example"),
                AutoRevert.decide(private, next, enabled = true, mayStart = true)
            )
        }
        // The system reports the mode in lower case, but don't depend on it
        assertEquals(
            Decision.Schedule("a.example"),
            AutoRevert.decide(DnsSetting("HOSTNAME", "a.example"), off, enabled = true, mayStart = true)
        )
    }

    @Test
    fun nothingIsScheduledWhenDisabledOrRequestedByAnotherApp() {
        assertEquals(Decision.Keep, AutoRevert.decide(private, off, enabled = false, mayStart = true))
        assertEquals(Decision.Keep, AutoRevert.decide(private, off, enabled = true, mayStart = false))
    }

    @Test
    fun switchingBetweenOffAndAutoKeepsPendingSwitchBack() {
        assertEquals(Decision.Keep, AutoRevert.decide(off, auto, enabled = true, mayStart = true))
        assertEquals(Decision.Keep, AutoRevert.decide(auto, off, enabled = true, mayStart = true))
    }

    @Test
    fun nothingToSwitchBackToWithoutPreviousServer() {
        for (provider in listOf(null, "")) {
            assertEquals(
                Decision.Keep,
                AutoRevert.decide(DnsSetting(DNS_MODE_PRIVATE, provider), off, enabled = true, mayStart = true)
            )
        }
    }

    @Test
    fun turningPrivateDnsOnCancelsPendingSwitchBack() {
        // Also when another app or the feature being disabled would not schedule one
        for (previous in listOf(off, auto, private)) {
            for (mayStart in listOf(true, false)) {
                assertEquals(
                    Decision.Cancel,
                    AutoRevert.decide(previous, DnsSetting(DNS_MODE_PRIVATE, "c.example"), enabled = false, mayStart = mayStart)
                )
            }
        }
    }
}
