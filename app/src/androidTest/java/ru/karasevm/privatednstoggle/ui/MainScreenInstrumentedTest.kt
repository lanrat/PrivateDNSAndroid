package ru.karasevm.privatednstoggle.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.Device
import ru.karasevm.privatednstoggle.PrivateDnsSettingsRule
import ru.karasevm.privatednstoggle.model.DnsServer

/** The app's screens, with screenshots for review; the status icons need internet access */
@RunWith(AndroidJUnit4::class)
class MainScreenInstrumentedTest {

    @get:Rule
    val settingsRule = PrivateDnsSettingsRule()

    private val device = UiDevice.getInstance(Device.instrumentation)

    @Before
    fun setUp(): Unit = runBlocking {
        Device.setSavedServers(
            DnsServer(server = "dns.google", label = "Google"),
            DnsServer(server = "one.one.one.one", label = "Cloudflare"),
            DnsServer(server = "does-not-exist.invalid", label = "Broken"),
        )
        Device.shell("cmd uimode night no")
        Device.shell("input keyevent KEYCODE_WAKEUP")
        Device.shell("wm dismiss-keyguard")
    }

    @After
    fun tearDown() {
        Device.shell("cmd uimode night no")
    }

    private fun countWithDescription(prefix: String) = device.findObjects(By.descStartsWith(prefix)).size

    private fun waitForStatuses() {
        val done = Device.waitUntil(45_000) {
            countWithDescription("Working") >= 2 && countWithDescription("Not working") >= 1
        }
        assertTrue(
            "statuses: ${device.findObjects(By.res(Device.packageName, "statusIcon")).map { it.contentDescription }}",
            done
        )
    }

    @Test
    fun serverListShowsStatusesAndDialogs() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text("Google")), 10_000))
            waitForStatuses()
            Device.screenshot("main-light")

            // Details of the broken server
            device.findObject(By.descStartsWith("Not working")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Test again")), 5_000))
            Device.screenshot("status-details")
            device.pressBack()

            // Options dialog
            device.findObject(By.desc("Options")).click()
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("Turn Private DNS back on")), 5_000))
            Device.screenshot("options")
            device.pressBack()

            // Dark mode; the statuses survive the recreation
            Device.shell("cmd uimode night yes")
            Thread.sleep(2_000)
            assertNotNull(device.wait(Until.findObject(By.text("Google")), 10_000))
            waitForStatuses()
            Device.screenshot("main-dark")
        }
    }

    @Test
    fun serverPickerFromTheTile() {
        ActivityScenario.launch(SettingsDialogActivity::class.java).use {
            assertNotNull(device.wait(Until.findObject(By.text("Cloudflare")), 10_000))
            assertNotNull(device.findObject(By.text("Off for…")))
            Device.screenshot("server-picker")
        }
    }
}
