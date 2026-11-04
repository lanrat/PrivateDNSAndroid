package ru.karasevm.privatednstoggle.util

import android.content.SharedPreferences
import ru.karasevm.privatednstoggle.util.PreferenceHelper.allowExternalControl
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertMinutes
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock

/**
 * The user's options. An interface so the logic using them can be tested with a fake.
 */
interface AppPreferences {
    /** Which modes the tile cycles through, one of the PrivateDNSUtils.AUTO_MODE_OPTION_* */
    var autoMode: Int
    var requireUnlock: Boolean
    var allowExternalControl: Boolean
    var autoRevertEnabled: Boolean

    /** One of [AutoRevert.PRESET_MINUTES] */
    var autoRevertMinutes: Int
}

/** [AppPreferences] stored in the app's shared preferences, see [PreferenceHelper] */
class SharedAppPreferences(private val prefs: SharedPreferences) : AppPreferences {
    override var autoMode: Int
        get() = prefs.autoMode
        set(value) { prefs.autoMode = value }
    override var requireUnlock: Boolean
        get() = prefs.requireUnlock
        set(value) { prefs.requireUnlock = value }
    override var allowExternalControl: Boolean
        get() = prefs.allowExternalControl
        set(value) { prefs.allowExternalControl = value }
    override var autoRevertEnabled: Boolean
        get() = prefs.autoRevertEnabled
        set(value) { prefs.autoRevertEnabled = value }
    override var autoRevertMinutes: Int
        get() = prefs.autoRevertMinutes
        set(value) { prefs.autoRevertMinutes = value }
}
