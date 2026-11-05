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

    @get:Rule
    val evidence = FailureEvidenceRule()

    private val commandOutput = StringBuilder()

    private fun statusbar(command: String) {
        commandOutput.append("$ cmd statusbar $command\n").append(Device.shell("cmd statusbar $command"))
    }

    private val tile = "${Device.packageName}/ru.karasevm.privatednstoggle.service.DnsTileService"

    @Before
    fun setUp(): Unit = runBlocking {
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
        statusbar("add-tile $tile")
        // SystemUI binds a new tile when the panel shows it; clicking right away may do nothing
        Thread.sleep(2_000)
        statusbar("expand-settings")
        Thread.sleep(2_000)
        statusbar("collapse")
        Thread.sleep(1_000)
    }

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= 33) Device.shell("cmd statusbar remove-tile $tile")
    }

    private fun clickAndWaitFor(description: String, condition: () -> Boolean) {
        statusbar("click-tile $tile")
        assertTrue(
            "$description; mode=${Device.privateDnsMode()} specifier=${Device.privateDnsSpecifier()}\n$commandOutput",
            Device.waitUntil(15_000, condition)
        )
    }

    @Test
    fun tappingTheTileCyclesServers() {
        clickAndWaitFor("first server") { Device.privateDnsMode() == "hostname" }
        assertEquals("dns.google", Device.privateDnsSpecifier())
        clickAndWaitFor("second server") { Device.privateDnsSpecifier() == "one.one.one.one" }
        clickAndWaitFor("off") { Device.privateDnsMode() == "off" }
    }
}
