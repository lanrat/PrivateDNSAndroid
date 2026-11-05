package ru.karasevm.privatednstoggle

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

/** Taps the real quick settings tile through SystemUI's shell commands (Android 13+) */
@RunWith(AndroidJUnit4::class)
class TileInstrumentedTest {

    @get:Rule
    val settingsRule = PrivateDnsSettingsRule()

    private val tile = "${Device.packageName}/ru.karasevm.privatednstoggle.service.DnsTileService"

    @Before
    fun setUp() = runBlocking {
        assumeTrue("cmd statusbar click-tile needs Android 13", Build.VERSION.SDK_INT >= 33)
        Device.setSavedServers(DnsServer(server = "dns.google"), DnsServer(server = "one.one.one.one"))
        Device.context.app.preferences.apply {
            autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_OFF
            requireUnlock = false
            autoRevertEnabled = false
        }
        Device.setPrivateDns("off", null)
        Device.shell("input keyevent KEYCODE_WAKEUP")
        Device.shell("wm dismiss-keyguard")
        Device.shell("cmd statusbar add-tile $tile")
    }

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= 33) Device.shell("cmd statusbar remove-tile $tile")
    }

    @Test
    fun tappingTheTileCyclesServers() {
        Device.shell("cmd statusbar click-tile $tile")
        assertTrue(Device.waitUntil { Device.privateDnsMode() == "hostname" })
        assertEquals("dns.google", Device.privateDnsSpecifier())

        Device.shell("cmd statusbar click-tile $tile")
        assertTrue(Device.waitUntil { Device.privateDnsSpecifier() == "one.one.one.one" })

        Device.shell("cmd statusbar click-tile $tile")
        assertTrue(Device.waitUntil { Device.privateDnsMode() == "off" })
    }
}
