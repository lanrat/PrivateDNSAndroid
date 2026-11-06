package ru.karasevm.privatednstoggle.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.karasevm.privatednstoggle.Device
import ru.karasevm.privatednstoggle.FailureEvidenceRule
import ru.karasevm.privatednstoggle.PrivateDnsSettingsRule
import ru.karasevm.privatednstoggle.model.DnsServer

/** The app's screens, with screenshots for review; the status icons need internet access */
@RunWith(AndroidJUnit4::class)
class MainScreenInstrumentedTest {

    @get:Rule
    val settingsRule = PrivateDnsSettingsRule()

    @get:Rule
    val evidence = FailureEvidenceRule()

    private val device = UiDevice.getInstance(Device.instrumentation)

    @Before
    fun setUp(): Unit = runBlocking {
        Device.setSavedServers(
            DnsServer(server = "dns.google", label = "Google"),
            DnsServer(server = "one.one.one.one", label = "Cloudflare"),
            DnsServer(server = "does-not-exist.invalid", label = "Broken"),
        )
        Device.shell("input keyevent KEYCODE_WAKEUP")
        Device.shell("wm dismiss-keyguard")
        dismissNotRespondingDialog()
    }

    @After
    fun tearDown() = setAppNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    /**
     * Dark mode for the app only. Changing it system-wide (cmd uimode) restarts the launcher
     * and System UI, which slow emulators may not finish before the next test.
     */
    private fun setAppNightMode(mode: Int) =
        Device.instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }

    /** A slow emulator may show "isn't responding" for a system app; keep waiting */
    private fun dismissNotRespondingDialog() {
        if (device.hasObject(By.textContains("isn't responding"))) {
            device.findObject(By.text("Wait"))?.click() ?: device.pressBack()
        }
    }

    private fun countWithDescription(prefix: String) = device.findObjects(By.descStartsWith(prefix)).size

    private fun waitForStatuses() {
        val done = Device.waitUntil(45_000) {
            countWithDescription("Working") >= 2 && countWithDescription("Not working") >= 1
        }
        assertTrue(
            "statuses: ${device.findObjects(By.res(Device.packageName, "statusIcon")).map { it.contentDescription }}" +
                ", descriptions: ${device.findObjects(By.descContains(" ")).map { it.contentDescription }}",
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

            // Options dialog, from the toolbar or, on narrow screens, the overflow menu
            val options = device.findObject(By.desc("Options"))
            if (options != null) {
                options.click()
            } else {
                device.findObject(By.desc("More options")).click()
                device.wait(Until.findObject(By.text("Options")), 5_000).click()
            }
            // The dialog's first line; later ones may be below the fold on small screens
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("Choose which options")), 5_000))
            Device.screenshot("options")
            device.pressBack()
        }
    }

    @Test
    fun addDialogSuggestsProvidersNotYetSaved() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull(device.wait(Until.findObject(By.text("Google")), 10_000))
            device.findObject(By.desc("Add Server")).click()
            val group = device.wait(Until.findObject(By.res(Device.packageName, "suggestionGroup")), 5_000)
            assertNotNull(group)
            // Google and Cloudflare are saved in setUp
            assertEquals(listOf("Quad9", "AdGuard", "Mullvad"), group.children.map { it.text })
            Device.screenshot("add-server")

            group.findObject(By.text("Quad9")).click()
            val address = device.findObject(By.res(Device.packageName, "editTextServerAddr"))
            assertTrue(Device.waitUntil { address.text == "dns.quad9.net" })
            assertEquals("Quad9", device.findObject(By.res(Device.packageName, "editTextServerHint")).text)
        }
    }

    @Test
    fun openSourceLicenses() {
        ActivityScenario.launch(AboutLibsActivity::class.java).use {
            // Sorted by name, so this one is at the top
            val first = device.wait(Until.findObject(By.text("AboutLibraries Core Library")), 10_000)
            assertNotNull(first)
            assertNotNull(device.findObject(By.textContains("Apache License 2.0")))
            Device.screenshot("licenses")

            first.click()
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("Apache License")), 5_000))
            Device.screenshot("license-text")
            device.findObject(By.text("OK")).click()
        }
    }

    @Test
    fun permissionExplanation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Shown when the permission is missing; the tests have it, so show it directly
            scenario.onActivity { showPermissionExplanationDialog(it) }
            assertNotNull(device.wait(Until.findObject(By.text("Permission Required")), 5_000))
            assertNotNull(device.findObject(By.textContains("Shizuku")))
            Device.screenshot("permission-dialog")
            device.findObject(By.text("OK")).click()
            assertTrue(device.wait(Until.gone(By.text("Permission Required")), 5_000))
        }
    }

    @Test
    fun serverListInDarkMode() {
        setAppNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull(device.wait(Until.findObject(By.text("Google")), 10_000))
            waitForStatuses()
            Device.screenshot("main-dark")
        }
    }

    @Test
    fun serverPickerFromTheTile() {
        // "Off for…" is only offered with a hostname to switch back to
        Device.setPrivateDns("off", "dns.google")
        ActivityScenario.launch(SettingsDialogActivity::class.java).use {
            assertNotNull(device.wait(Until.findObject(By.text("Cloudflare")), 10_000))
            assertNotNull(device.findObject(By.text("Off for…")))
            Device.screenshot("server-picker")
        }
    }
}
