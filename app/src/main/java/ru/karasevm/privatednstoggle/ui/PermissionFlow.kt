package ru.karasevm.privatednstoggle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.util.ShizukuUtil.grantPermissionWithShizuku

/**
 * Gets the WRITE_SECURE_SETTINGS permission for [activity] whenever it resumes without it:
 * through Shizuku when it runs, otherwise (or when that fails) by showing
 * [PermissionExplanationDialog] with the manual options. Dismisses the dialog once the
 * permission is granted, e.g. with adb while the app was in the background.
 *
 * Create it in the activity's onCreate, after super.onCreate; it follows the activity's
 * lifecycle by itself.
 */
class PermissionFlow(private val activity: AppCompatActivity) :
    DefaultLifecycleObserver,
    Shizuku.OnRequestPermissionResultListener,
    SavedStateRegistry.SavedStateProvider {

    private var dialog: AlertDialog? = null

    // Whether Shizuku was asked for access / used to grant the permission since the activity
    // was created. Kept across recreation, so the prompt and toasts don't repeat.
    private var shizukuRequested = false
    private var shizukuGrantAttempted = false

    init {
        activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)?.let {
            shizukuRequested = it.getBoolean(STATE_SHIZUKU_REQUESTED)
            shizukuGrantAttempted = it.getBoolean(STATE_SHIZUKU_GRANT_ATTEMPTED)
        }
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY, this)
        activity.lifecycle.addObserver(this)
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun saveState() = Bundle().apply {
        putBoolean(STATE_SHIZUKU_REQUESTED, shizukuRequested)
        putBoolean(STATE_SHIZUKU_GRANT_ATTEMPTED, shizukuGrantAttempted)
    }

    private fun hasPermission() =
        activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    override fun onResume(owner: LifecycleOwner) {
        if (hasPermission()) {
            dialog?.dismiss()
            return
        }
        if (!Shizuku.pingBinder()) {
            showDialog()
            return
        }
        val legacyShizuku = Shizuku.isPreV11() || Shizuku.getVersion() < 11
        val shizukuAllowed = if (legacyShizuku) {
            activity.checkSelfPermission(ShizukuProvider.PERMISSION) == PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
        when {
            // Only try once per activity, a failure shows the dialog instead
            shizukuAllowed -> if (!shizukuGrantAttempted) grantWithShizuku() else showDialog()

            // Ask once per activity, otherwise every resume would show the prompt again
            !shizukuRequested && !Shizuku.shouldShowRequestPermissionRationale() -> {
                shizukuRequested = true
                if (legacyShizuku) {
                    activity.requestPermissions(arrayOf(ShizukuProvider.PERMISSION), REQUEST_CODE)
                } else {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            }

            // The user denied Shizuku access permanently, explain the other options
            else -> showDialog()
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        // Already handled, e.g. by onResume after the Shizuku dialog closed
        if (hasPermission()) return
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            if (!shizukuGrantAttempted) grantWithShizuku()
        } else {
            showDialog()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Shown again in onResume if still needed
        dialog?.dismiss()
        dialog = null
        Shizuku.removeRequestPermissionResultListener(this)
    }

    private fun grantWithShizuku() {
        shizukuGrantAttempted = true
        if (grantPermissionWithShizuku(activity)) {
            dialog?.dismiss()
            Toast.makeText(activity, R.string.shizuku_success_toast, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, R.string.shizuku_failure_toast, Toast.LENGTH_SHORT).show()
            showDialog()
        }
    }

    private fun showDialog() {
        // onResume runs again after returning from the browser, don't stack dialogs
        if (dialog?.isShowing == true) return

        val view = ComposeView(activity).apply {
            setContent {
                AppTheme {
                    PermissionExplanationDialog(
                        onDismiss = { dialog?.dismiss() },
                        onConfirm = { dialog?.dismiss() }
                    )
                }
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .show()
            .apply { window?.setBackgroundDrawableResource(android.R.color.transparent) }
    }

    private companion object {
        const val REQUEST_CODE = 1
        const val STATE_KEY = "permission_flow"
        const val STATE_SHIZUKU_REQUESTED = "shizuku_requested"
        const val STATE_SHIZUKU_GRANT_ATTEMPTED = "shizuku_grant_attempted"
    }
}
