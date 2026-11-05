package ru.karasevm.privatednstoggle

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import ru.karasevm.privatednstoggle.data.database.DnsServerRoomDatabase
import ru.karasevm.privatednstoggle.model.DnsServer

/** Helpers for instrumented tests on a device or emulator */
object Device {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val packageName: String get() = context.packageName

    /** Runs [command] as the shell user (like adb shell) and returns its output */
    fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    /** Grants WRITE_SECURE_SETTINGS, like users do with adb or Shizuku */
    fun grantSecureSettings() {
        shell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
    }

    fun privateDnsMode(): String? = Settings.Global.getString(context.contentResolver, "private_dns_mode")
    fun privateDnsSpecifier(): String? = Settings.Global.getString(context.contentResolver, "private_dns_specifier")

    /** Sets the system setting through the shell, independent of the code under test */
    fun setPrivateDns(mode: String?, specifier: String?) {
        putOrDelete("private_dns_mode", mode)
        putOrDelete("private_dns_specifier", specifier)
    }

    private fun putOrDelete(name: String, value: String?) {
        shell(if (value == null) "settings delete global $name" else "settings put global $name $value")
    }

    /** Polls [condition] until it is true or [timeoutMs] passed; returns its last value */
    fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /** Saves a screenshot for the emulator workflow, which collects them as an artifact */
    fun screenshot(name: String) {
        shell("mkdir -p $SCREENSHOTS")
        shell("screencap -p $SCREENSHOTS/api${Build.VERSION.SDK_INT}-$name.png")
    }

    /** Replaces the app's saved servers (the real database the app's screens use) */
    suspend fun setSavedServers(vararg servers: DnsServer) {
        val dao = DnsServerRoomDatabase.getDatabase(context).dnsServerDao()
        dao.deleteAll()
        dao.insertAllIfAbsent(servers.toList())
    }

    const val SCREENSHOTS = "/data/local/tmp/screenshots"
}

/** Restores the system Private DNS setting after each test */
class PrivateDnsSettingsRule : ExternalResource() {
    private var mode: String? = null
    private var specifier: String? = null

    override fun before() {
        mode = Device.privateDnsMode()
        specifier = Device.privateDnsSpecifier()
        Device.grantSecureSettings()
    }

    override fun after() = Device.setPrivateDns(mode, specifier)
}
