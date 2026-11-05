package ru.karasevm.privatednstoggle

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.model.DnsServer

/** The shortcut entry points, including the opt-in for other apps */
@RunWith(AndroidJUnit4::class)
class ShortcutInstrumentedTest {

    @get:Rule
    val settingsRule = PrivateDnsSettingsRule()

    private val internal = "${Device.packageName}/ru.karasevm.privatednstoggle.ShortcutHandlerActivity"
    private val external = "${Device.packageName}/${ShortcutHandlerActivity.EXTERNAL_ACTIVITY}"

    @Before
    fun setUp(): Unit = runBlocking {
        Device.setSavedServers(DnsServer(server = "dns.google"))
        Device.context.app.preferences.allowExternalControl = false
        Device.context.app.preferences.autoRevertEnabled = false
        Device.setPrivateDns("hostname", "dns.google")
        Device.shell("input keyevent KEYCODE_WAKEUP")
        Device.shell("wm dismiss-keyguard")
    }

    @After
    fun tearDown() {
        Device.context.app.preferences.allowExternalControl = false
    }

    private fun startOwnShortcut(action: String) = Device.context.startActivity(
        Intent(Device.context, ShortcutHandlerActivity::class.java)
            .setAction(ShortcutHandlerActivity.ACTION_SHORTCUT)
            .setData(Uri.parse(action))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /**
     * Starts the shortcut as another app would: from the shell's uid. Without -W, which waits
     * forever on Android 9 for an activity that finishes without drawing.
     */
    private fun startFromShell(component: String, action: String) {
        Device.shell("am start -n $component -a ${ShortcutHandlerActivity.ACTION_SHORTCUT} -d $action")
    }

    @Test
    fun ownShortcutsWork() {
        startOwnShortcut("privatednstoggle://turn_off")
        assertTrue(Device.waitUntil { Device.privateDnsMode() == "off" })

        startOwnShortcut("privatednstoggle://turn_on")
        assertTrue(Device.waitUntil { Device.privateDnsMode() == "hostname" })
        assertEquals("dns.google", Device.privateDnsSpecifier())
    }

    @Test
    fun otherAppsCantStartTheInternalActivity() {
        // am reports the permission denial on stderr, which isn't captured; check the effect
        startFromShell(internal, "privatednstoggle://turn_off")
        Thread.sleep(2_000)
        assertEquals("hostname", Device.privateDnsMode())
    }

    @Test
    fun otherAppsNeedTheOptIn() {
        startFromShell(external, "privatednstoggle://turn_off")
        Thread.sleep(2_000)
        assertEquals("opt-in is off", "hostname", Device.privateDnsMode())

        Device.context.app.preferences.allowExternalControl = true
        startFromShell(external, "privatednstoggle://turn_off")
        assertTrue(Device.waitUntil { Device.privateDnsMode() == "off" })
    }
}
