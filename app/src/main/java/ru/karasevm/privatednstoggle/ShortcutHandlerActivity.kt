package ru.karasevm.privatednstoggle

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.service.ShortcutService
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.allowExternalControl

/**
 * Entry point for shortcuts.
 *
 * The activity itself is not exported. The app's own shortcuts (static, pinned, and those made
 * through the launcher's shortcut picker) still reach it, because the launcher starts shortcuts
 * with the publishing app's identity.
 *
 * Other apps can only reach it through the exported [EXTERNAL_ACTIVITY] alias. Requests that
 * come through the alias only change Private DNS when the user enabled
 * [PreferenceHelper.allowExternalControl]. The alias also serves
 * [Intent.ACTION_CREATE_SHORTCUT], which only describes a shortcut and changes nothing.
 */
class ShortcutHandlerActivity : AppCompatActivity() {

    private var pickerDialog: AlertDialog? = null

    private class ShortcutOption(
        val action: String,
        @StringRes val label: Int,
        @DrawableRes val icon: Int
    )

    private val shortcutOptions = listOf(
        ShortcutOption(
            ShortcutService.ACTION_DO_CYCLE,
            R.string.shortcut_do_cycle,
            R.drawable.ic_shortcut_next_24dp
        ),
        ShortcutOption(
            ShortcutService.ACTION_SWITCH_MODE,
            R.string.shortcut_switch_mode,
            R.drawable.ic_shortcut_mode_24dp
        ),
        ShortcutOption(
            ShortcutService.ACTION_TURN_ON,
            R.string.shortcut_turn_on,
            R.drawable.ic_private_black_24dp
        ),
        ShortcutOption(
            ShortcutService.ACTION_TURN_OFF,
            R.string.shortcut_turn_off,
            R.drawable.ic_off_black_24dp
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            showShortcutPicker()
            return
        }
        handleShortcut(intent)
        finish()
    }

    override fun onDestroy() {
        pickerDialog?.dismiss()
        pickerDialog = null
        super.onDestroy()
    }

    /**
     * Starts [ShortcutService] for the action in the intent data, if the caller is allowed to.
     */
    private fun handleShortcut(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.toString() !in ShortcutService.ACTIONS) {
            Log.w(TAG, "handleShortcut: unknown action $data")
            return
        }
        // An alias launch means the intent came from another app, not from our own shortcuts
        val isExternal = componentName.className != ShortcutHandlerActivity::class.java.name
        if (isExternal && !PreferenceHelper.defaultPreference(this).allowExternalControl) {
            Log.i(TAG, "handleShortcut: external control is disabled, ignoring $data")
            Toast.makeText(this, R.string.external_control_disabled_toast, Toast.LENGTH_LONG)
                .show()
            return
        }
        startService(Intent(this, ShortcutService::class.java).setData(data))
    }

    /**
     * Shows the list of shortcuts for [Intent.ACTION_CREATE_SHORTCUT] and returns the chosen one.
     */
    private fun showShortcutPicker() {
        pickerDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shortcut_picker_title)
            .setItems(shortcutOptions.map { getString(it.label) }.toTypedArray()) { _, which ->
                setResult(RESULT_OK, createShortcutResult(shortcutOptions[which]))
            }
            .setOnDismissListener {
                // Leaves the result as RESULT_CANCELED unless an item was picked.
                // Dismissal during a configuration change recreates the activity, so keep it.
                if (!isChangingConfigurations) finish()
            }
            .show()
    }

    /**
     * Builds the result for [Intent.ACTION_CREATE_SHORTCUT].
     *
     * Launchers use the pinned shortcut request, which they start with this app's identity,
     * so it can target the non-exported activity. Other apps (e.g. Tasker) only read the legacy
     * [Intent.EXTRA_SHORTCUT_INTENT] and start it themselves, so that one points at the
     * exported alias and is subject to the external control setting.
     */
    private fun createShortcutResult(option: ShortcutOption): Intent {
        val shortcutIntent = Intent(this, ShortcutHandlerActivity::class.java)
            .setAction(ACTION_SHORTCUT)
            .setData(option.action.toUri())
        val label = getString(option.label)
        val shortcut = ShortcutInfoCompat.Builder(this, "created_" + option.action.substringAfter("://"))
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(this, option.icon))
            .setIntent(shortcutIntent)
            .build()
        @Suppress("DEPRECATION")
        return ShortcutManagerCompat.createShortcutResultIntent(this, shortcut)
            .putExtra(
                Intent.EXTRA_SHORTCUT_INTENT,
                Intent(shortcutIntent).setClassName(this, EXTERNAL_ACTIVITY)
            )
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            .putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, option.icon)
            )
    }

    companion object {
        private const val TAG = "ShortcutHandlerActivity"

        /** Action used by the app's shortcuts. Any action works with explicit intents. */
        const val ACTION_SHORTCUT = "ru.karasevm.privatednstoggle.action.SHORTCUT"

        /** Exported alias of this activity that other apps can start */
        const val EXTERNAL_ACTIVITY = "ru.karasevm.privatednstoggle.ExternalShortcutHandlerActivity"
    }
}
