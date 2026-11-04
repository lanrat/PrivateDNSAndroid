package ru.karasevm.privatednstoggle.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object PreferenceHelper {

    const val DNS_SERVERS = "dns_servers"
    const val AUTO_MODE = "auto_mode"
    const val REQUIRE_UNLOCK = "require_unlock"
    const val ALLOW_EXTERNAL_CONTROL = "allow_external_control"
    const val AUTO_REVERT_ENABLED = "auto_revert_enabled"
    const val AUTO_REVERT_MINUTES = "auto_revert_minutes"

    fun defaultPreference(context: Context): SharedPreferences =
        context.getSharedPreferences("app_prefs", 0)

    private inline fun SharedPreferences.editMe(operation: (SharedPreferences.Editor) -> Unit) {
        edit {
            operation(this)
        }
    }

    private fun SharedPreferences.Editor.put(pair: Pair<String, Any>) {
        val key = pair.first
        when (val value = pair.second) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            else -> error("Only primitive types can be stored in SharedPreferences, got ${value.javaClass}")
        }
    }

    var SharedPreferences.dns_servers
        get() = getString(DNS_SERVERS, "")!!.split(",").toMutableList()
        set(items) {
            editMe {
                it.put(DNS_SERVERS to items.joinToString(separator = ","))
            }
        }


    var SharedPreferences.autoMode
        get() = getInt(AUTO_MODE, PrivateDNSUtils.AUTO_MODE_OPTION_OFF)
        set(value) {
            editMe {
                it.put(AUTO_MODE to value)
            }
        }

    var SharedPreferences.requireUnlock
        get() = getBoolean(REQUIRE_UNLOCK, false)
        set(value) {
            editMe {
                it.put(REQUIRE_UNLOCK to value)
            }
        }

    /** Whether other apps (e.g. automation apps) may change Private DNS via shortcut intents */
    var SharedPreferences.allowExternalControl
        get() = getBoolean(ALLOW_EXTERNAL_CONTROL, false)
        set(value) {
            editMe {
                it.put(ALLOW_EXTERNAL_CONTROL to value)
            }
        }

    /** Whether to switch back to Private DNS after the user turned it off or to auto */
    var SharedPreferences.autoRevertEnabled
        get() = getBoolean(AUTO_REVERT_ENABLED, false)
        set(value) {
            editMe {
                it.put(AUTO_REVERT_ENABLED to value)
            }
        }

    /** Minutes to wait before switching back */
    var SharedPreferences.autoRevertMinutes
        get() = getInt(AUTO_REVERT_MINUTES, AutoRevert.DEFAULT_MINUTES)
            .coerceIn(AutoRevert.MIN_MINUTES, AutoRevert.MAX_MINUTES)
        set(value) {
            editMe {
                it.put(AUTO_REVERT_MINUTES to value.coerceIn(AutoRevert.MIN_MINUTES, AutoRevert.MAX_MINUTES))
            }
        }
}
