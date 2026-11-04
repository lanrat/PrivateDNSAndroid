package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.karasevm.privatednstoggle.FakeAppPreferences
import ru.karasevm.privatednstoggle.FakeSwitchBackScheduler
import ru.karasevm.privatednstoggle.FakeSystemDnsSettings
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.data.FakeDnsServerDao
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_AUTO
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_OFF
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.DNS_MODE_PRIVATE
import ru.karasevm.privatednstoggle.util.ShortcutActions

class DnsControllerTest {

    private val settings = FakeSystemDnsSettings(DnsSetting(DNS_MODE_PRIVATE, "a.example"))
    private val preferences = FakeAppPreferences()
    private val switchBack = FakeSwitchBackScheduler()
    private var now = 1_000_000L
    private val controller = DnsController(
        settings,
        DnsServerRepository(
            FakeDnsServerDao(listOf(DnsServer(server = "a.example", label = "A"), DnsServer(server = "b.example")))
        ),
        preferences,
        switchBack,
        now = { now },
    )

    private fun private(server: String) = DnsSetting(DNS_MODE_PRIVATE, server)
    private val minutes = 60_000L

    @Test
    fun cycleAppliesTheNextSetting() = runTest {
        assertEquals(DnsChange.Applied(private("b.example")), controller.cycle())
        assertEquals(private("b.example"), settings.setting)
        assertEquals(DnsChange.Applied(DnsSetting(DNS_MODE_OFF, "b.example")), controller.cycle())
    }

    @Test
    fun cycleWithoutPermissionChangesNothing() = runTest {
        settings.permission = false
        assertEquals(DnsChange.PermissionMissing, controller.cycle())
        assertEquals(emptyList<DnsSetting>(), settings.writes)
    }

    @Test
    fun cycleWithoutEnabledServers() = runTest {
        val empty = DnsController(settings, DnsServerRepository(FakeDnsServerDao()), preferences, switchBack) { now }
        settings.setting = DnsSetting(DNS_MODE_OFF, null)
        assertEquals(DnsChange.NoEnabledServer, empty.cycle())
    }

    @Test
    fun turningOffSchedulesTheSwitchBackWhenEnabled() = runTest {
        preferences.autoRevertEnabled = true
        preferences.autoRevertMinutes = 15
        settings.setting = private("b.example")

        val change = controller.cycle()

        val at = now + 15 * minutes
        // Off keeps the hostname, like Android does
        assertEquals(DnsChange.Applied(DnsSetting(DNS_MODE_OFF, "b.example"), at), change)
        assertEquals(PendingSwitchBack(at, "b.example"), switchBack.stored)
        assertEquals(at, controller.pendingSwitchBackAt())
    }

    @Test
    fun noSwitchBackWhenDisabled() = runTest {
        settings.setting = private("b.example")
        assertEquals(DnsChange.Applied(DnsSetting(DNS_MODE_OFF, "b.example")), controller.cycle())
        assertNull(switchBack.stored)
    }

    @Test
    fun turningPrivateDnsOnCancelsThePendingSwitchBack() = runTest {
        switchBack.stored = PendingSwitchBack(now + minutes, "a.example")
        settings.setting = DnsSetting(DNS_MODE_OFF, "a.example")

        controller.select(private("b.example"))

        assertNull(switchBack.stored)
    }

    @Test
    fun otherAppsNeverScheduleAndCancelPending() = runTest {
        preferences.autoRevertEnabled = true
        switchBack.stored = PendingSwitchBack(now + minutes, "b.example")

        val change = controller.performShortcut(ShortcutActions.ACTION_TURN_OFF, fromOtherApp = true)

        assertEquals(DnsChange.Applied(DnsSetting(DNS_MODE_OFF, "a.example")), change)
        assertNull(switchBack.stored)
    }

    @Test
    fun ownShortcutsScheduleTheSwitchBack() = runTest {
        preferences.autoRevertEnabled = true
        val change = controller.performShortcut(ShortcutActions.ACTION_TURN_OFF, fromOtherApp = false)
        assertEquals(now + 5 * minutes, (change as DnsChange.Applied).switchBackAt)
    }

    @Test
    fun turnOffForWorksEvenWhenTheFeatureIsDisabled() {
        val change = controller.turnOffFor(30)

        assertEquals(DnsChange.Applied(DnsSetting(DNS_MODE_OFF, "a.example"), now + 30 * minutes), change)
        assertEquals(DnsSetting(DNS_MODE_OFF, "a.example"), settings.setting)
        assertEquals(PendingSwitchBack(now + 30 * minutes, "a.example"), switchBack.stored)
    }

    @Test
    fun turnOffForNeedsAHostname() {
        settings.setting = DnsSetting(DNS_MODE_AUTO, null)
        assertEquals(DnsChange.NothingToSwitchBackTo, controller.turnOffFor(30))
        assertEquals(emptyList<DnsSetting>(), settings.writes)
    }

    @Test
    fun aRevokedPermissionIsReportedNotThrown() {
        settings.permission = false
        assertEquals(DnsChange.PermissionMissing, controller.select(private("b.example")))
        assertEquals(DnsChange.PermissionMissing, controller.turnOffFor(30))
    }

    @Test
    fun dueSwitchBackTurnsPrivateDnsBackOn() {
        settings.setting = DnsSetting(DNS_MODE_OFF, "a.example")
        switchBack.stored = PendingSwitchBack(now, "b.example")

        assertEquals(private("b.example"), controller.runDueSwitchBack())

        assertEquals(private("b.example"), settings.setting)
        assertNull(switchBack.stored)
    }

    @Test
    fun switchBackThatIsNotDueIsOnlyRearmed() {
        settings.setting = DnsSetting(DNS_MODE_OFF, "a.example")
        switchBack.stored = PendingSwitchBack(now + 1, "b.example")

        assertNull(controller.runDueSwitchBack())

        assertEquals(DnsSetting(DNS_MODE_OFF, "a.example"), settings.setting)
        assertEquals(now + 1, switchBack.armedAt)
    }

    @Test
    fun dueSwitchBackKeepsWhatTheUserTurnedOnMeanwhile() {
        settings.setting = private("a.example")
        switchBack.stored = PendingSwitchBack(now, "b.example")

        assertNull(controller.runDueSwitchBack())

        assertEquals(private("a.example"), settings.setting)
        assertNull(switchBack.stored)
    }

    @Test
    fun dueSwitchBackWithoutPermissionIsDropped() {
        settings.setting = DnsSetting(DNS_MODE_OFF, "a.example")
        settings.permission = false
        switchBack.stored = PendingSwitchBack(now, "b.example")

        assertNull(controller.runDueSwitchBack())

        assertNull(switchBack.stored)
        assertEquals(emptyList<DnsSetting>(), settings.writes)
    }

    @Test
    fun cancelSwitchBack() {
        switchBack.stored = PendingSwitchBack(now + minutes, "b.example")
        controller.cancelSwitchBack()
        assertNull(controller.pendingSwitchBackAt())
    }

    @Test
    fun cancelSwitchBackToIgnoresCase() {
        switchBack.stored = PendingSwitchBack(now, "b.example")
        controller.cancelSwitchBackTo("a.example")
        assertEquals("b.example", switchBack.stored?.provider)
        controller.cancelSwitchBackTo("B.Example")
        assertNull(switchBack.stored)
    }

    @Test
    fun displayNameUsesTheLabel() = runTest {
        assertEquals("A", controller.displayName("a.example"))
        assertEquals("b.example", controller.displayName("b.example"))
        assertEquals("other.example", controller.displayName("other.example"))
    }
}
