package ru.karasevm.privatednstoggle

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.data.BackupManager
import ru.karasevm.privatednstoggle.data.DnsServerRepository
import ru.karasevm.privatednstoggle.data.LegacyServerMigration
import ru.karasevm.privatednstoggle.data.database.DnsServerRoomDatabase
import ru.karasevm.privatednstoggle.dns.AlarmSwitchBackScheduler
import ru.karasevm.privatednstoggle.dns.AndroidSystemDnsSettings
import ru.karasevm.privatednstoggle.dns.DnsController
import ru.karasevm.privatednstoggle.dns.DotServerProbe
import ru.karasevm.privatednstoggle.dns.ServerProbe
import ru.karasevm.privatednstoggle.util.AppPreferences
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.SharedAppPreferences

/**
 * The application, which also holds the app-wide objects (a small manual dependency
 * container). Get it with [Context.app].
 */
class PrivateDNSApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { DnsServerRoomDatabase.getDatabase(this) }

    val repository by lazy { DnsServerRepository(database.dnsServerDao()) }
    val preferences: AppPreferences by lazy {
        SharedAppPreferences(PreferenceHelper.defaultPreference(this))
    }
    val dnsController by lazy {
        DnsController(
            AndroidSystemDnsSettings(this),
            repository,
            preferences,
            AlarmSwitchBackScheduler(this)
        )
    }
    val backupManager by lazy { BackupManager(repository, preferences) }
    val serverProbe: ServerProbe by lazy { DotServerProbe() }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        if (BuildConfig.DEBUG){
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        appScope.launch {
            // Runs on every process start (also the tile's and receivers'), so never crash here
            runCatching {
                LegacyServerMigration.run(PreferenceHelper.defaultPreference(this@PrivateDNSApp), repository)
            }.onFailure { Log.e("PrivateDNSApp", "Migrating the legacy server list failed", it) }
        }
    }
}

/** The application object, which holds the app-wide objects */
val Context.app: PrivateDNSApp get() = applicationContext as PrivateDNSApp
