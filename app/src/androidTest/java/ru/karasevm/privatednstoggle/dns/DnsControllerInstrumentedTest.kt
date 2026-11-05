package ru.karasevm.privatednstoggle.dns

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.Device
import ru.karasevm.privatednstoggle.PrivateDnsSettingsRule
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.data.database.DnsServerRoomDatabase
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.service.AutoRevertReceiver
import ru.karasevm.privatednstoggle.util.AppPreferences
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

/** DnsController writing the real system setting, with the real alarm scheduler */
@RunWith(AndroidJUnit4::class)
class DnsControllerInstrumentedTest {

    @get:Rule
    val settingsRule = PrivateDnsSettingsRule()

    private val database = Room.inMemoryDatabaseBuilder(Device.context, DnsServerRoomDatabase::class.java).build()
    private val preferences = object : AppPreferences {
        override var autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_OFF
        override var requireUnlock = false
        override var allowExternalControl = false
        override var autoRevertEnabled = false
        override var autoRevertMinutes = AutoRevert.DEFAULT_MINUTES
    }
    private val scheduler = AlarmSwitchBackScheduler(Device.context)
    private val controller = DnsController(
        AndroidSystemDnsSettings(Device.context),
        DnsServerRepository(database.dnsServerDao()),
        preferences,
        scheduler,
    )

    @Before
    fun setUp() = runBlocking {
        database.dnsServerDao().insertAllIfAbsent(
            listOf(DnsServer(server = "dns.google"), DnsServer(server = "one.one.one.one"))
        )
        Device.setPrivateDns("off", "dns.google")
        scheduler.cancel()
    }

    @After
    fun tearDown() {
        scheduler.cancel()
        database.close()
    }

    @Test
    fun cycleWritesTheSystemSetting() = runBlocking {
        controller.cycle()
        assertEquals("hostname", Device.privateDnsMode())
        assertEquals("dns.google", Device.privateDnsSpecifier())

        controller.cycle()
        assertEquals("one.one.one.one", Device.privateDnsSpecifier())

        controller.cycle()
        assertEquals("off", Device.privateDnsMode())
        // Android keeps the hostname while off
        assertEquals("one.one.one.one", Device.privateDnsSpecifier())
    }

    @Test
    fun unsetModeReadsAsAuto() {
        Device.setPrivateDns(null, null)
        assertEquals(PrivateDNSUtils.DNS_MODE_AUTO, controller.current().mode)
    }

    @Test
    fun turnOffForSchedulesARealAlarm() {
        Device.setPrivateDns("hostname", "dns.google")

        val change = controller.turnOffFor(5) as DnsChange.Applied

        assertEquals("off", Device.privateDnsMode())
        assertNotNull(change.switchBackAt)
        assertEquals(change.switchBackAt, controller.pendingSwitchBackAt())
        assertTrue("alarm missing", Device.shell("dumpsys alarm").contains(AutoRevertReceiver.ACTION_REVERT))

        controller.cancelSwitchBack()
        assertNull(controller.pendingSwitchBackAt())
    }

    @Test
    fun dueSwitchBackRunsThroughTheReceiver() {
        Device.setPrivateDns("off", "dns.google")
        scheduler.schedule(PendingSwitchBack(System.currentTimeMillis() - 1, "one.one.one.one"))

        // What the alarm delivers
        Device.context.sendBroadcast(
            Intent(Device.context, AutoRevertReceiver::class.java).setAction(AutoRevertReceiver.ACTION_REVERT)
        )

        assertTrue(Device.waitUntil { Device.privateDnsMode() == "hostname" })
        assertEquals("one.one.one.one", Device.privateDnsSpecifier())
        assertNull(scheduler.pending())
    }

    @Test
    fun selectAndSwitchBackDecision() {
        preferences.autoRevertEnabled = true
        Device.setPrivateDns("hostname", "dns.google")

        val change = controller.select(DnsSetting(PrivateDNSUtils.DNS_MODE_AUTO, null)) as DnsChange.Applied

        assertEquals("opportunistic", Device.privateDnsMode())
        assertEquals(PendingSwitchBack(change.switchBackAt!!, "dns.google"), scheduler.pending())
    }
}
