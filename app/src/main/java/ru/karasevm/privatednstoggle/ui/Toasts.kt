package ru.karasevm.privatednstoggle.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.app
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.DnsSetting

/**
 * Shows a toast from any thread. Android 13+ only shows toasts from apps in the background
 * (tile, receivers) when they may post notifications, which this app doesn't ask for, so
 * those mostly appear on older versions; the tile shows the state anyway.
 */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val appContext = applicationContext
    val show = { Toast.makeText(appContext, message, duration).show() }
    // Right away on the main thread, while a calling activity is still in front
    if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
}

/** "Private DNS will turn back on at about 14:05" */
fun Context.showSwitchBackScheduledToast(at: Long) =
    showToast(AutoRevert.scheduledMessage(this, at), Toast.LENGTH_LONG)

/** "Private DNS set to <server>" after a switch-back */
suspend fun Context.showSwitchedBackToast(setting: DnsSetting) {
    val provider = setting.provider ?: return
    val name = app.dnsController.displayName(provider)
    showToast(getString(R.string.set_to_provider_toast, name), Toast.LENGTH_LONG)
}
