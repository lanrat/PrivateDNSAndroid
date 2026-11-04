package ru.karasevm.privatednstoggle.dns

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

/**
 * The system's Private DNS setting. An interface so [DnsController] can be tested with a fake.
 */
interface SystemDnsSettings {
    /** Whether the app may change the setting (WRITE_SECURE_SETTINGS) */
    fun hasPermission(): Boolean

    /** The current setting, with an unset mode resolved the way Android does */
    fun read(): DnsSetting

    /**
     * Changes the setting, see [PrivateDNSUtils.applyDnsSetting].
     * @throws SecurityException without the permission
     */
    fun write(setting: DnsSetting)

    /** Emits whenever the setting changes, by this app or anything else, while collected */
    fun changes(): Flow<Unit>
}

/** [SystemDnsSettings] backed by Settings.Global */
class AndroidSystemDnsSettings(context: Context) : SystemDnsSettings {
    private val context = context.applicationContext
    private val contentResolver = this.context.contentResolver

    override fun hasPermission() = PrivateDNSUtils.checkForPermission(context)

    override fun read() = DnsSetting(
        PrivateDNSUtils.getPrivateMode(contentResolver),
        PrivateDNSUtils.getPrivateProvider(contentResolver)
    )

    override fun write(setting: DnsSetting) = PrivateDNSUtils.applyDnsSetting(contentResolver, setting)

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(PrivateDNSUtils.PRIVATE_DNS_MODE_URI, false, observer)
        contentResolver.registerContentObserver(PrivateDNSUtils.PRIVATE_DNS_PROVIDER_URI, false, observer)
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }
}
