package ru.karasevm.privatednstoggle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.karasevm.privatednstoggle.dns.PendingSwitchBack
import ru.karasevm.privatednstoggle.dns.SwitchBackScheduler
import ru.karasevm.privatednstoggle.dns.SystemDnsSettings
import ru.karasevm.privatednstoggle.util.AppPreferences
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

/** In-memory system setting; writes behave like PrivateDNSUtils.applyDnsSetting */
class FakeSystemDnsSettings(
    var setting: DnsSetting = DnsSetting(PrivateDNSUtils.DNS_MODE_AUTO, null),
    var permission: Boolean = true,
) : SystemDnsSettings {
    val writes = mutableListOf<DnsSetting>()

    override fun hasPermission() = permission
    override fun read() = setting
    override fun write(setting: DnsSetting) {
        if (!permission) throw SecurityException("WRITE_SECURE_SETTINGS")
        writes += setting
        // The hostname is only written for private mode, Android keeps it otherwise
        this.setting = if (setting.mode == PrivateDNSUtils.DNS_MODE_PRIVATE) {
            setting
        } else {
            DnsSetting(setting.mode, this.setting.provider)
        }
    }

    override fun changes(): Flow<Unit> = emptyFlow()
}

class FakeAppPreferences(
    override var autoMode: Int = PrivateDNSUtils.AUTO_MODE_OPTION_OFF,
    override var requireUnlock: Boolean = false,
    override var allowExternalControl: Boolean = false,
    override var autoRevertEnabled: Boolean = false,
    override var autoRevertMinutes: Int = AutoRevert.DEFAULT_MINUTES,
) : AppPreferences

class FakeSwitchBackScheduler : SwitchBackScheduler {
    var stored: PendingSwitchBack? = null
    var armedAt: Long? = null

    override fun pending() = stored
    override fun schedule(switchBack: PendingSwitchBack) {
        stored = switchBack
        armedAt = switchBack.at
    }

    override fun rearm(switchBack: PendingSwitchBack) {
        armedAt = switchBack.at
    }

    override fun cancel() {
        stored = null
        armedAt = null
    }
}
