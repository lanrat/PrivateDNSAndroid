package ru.karasevm.privatednstoggle.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R

/**
 * Shows how to grant the WRITE_SECURE_SETTINGS permission: with Shizuku or with adb, with
 * links to both. It can't be cancelled by tapping outside, only closed with OK.
 */
fun showPermissionExplanationDialog(context: Context): AlertDialog {
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.permission_dialog_title)
        .setMessage(permissionExplanation(context))
        .setPositiveButton(R.string.ok, null)
        .setCancelable(false)
        .show()
    // Makes the links in the message clickable
    dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    return dialog
}

private fun permissionExplanation(context: Context): CharSequence = SpannableStringBuilder().apply {
    append(context.getString(R.string.permission_intro)).append("\n\n")
    inSpans(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.1f)) {
        append(context.getString(R.string.permission_how_title))
    }
    append("\n").append(context.getString(R.string.permission_how_details)).append("\n\n")
    appendWithLink(
        context, R.string.permission_how_1, R.string.permission_shizuku_text, R.string.permission_shizuku_url
    )
    append("\n")
    appendWithLink(
        context, R.string.permission_how_2, R.string.permission_manual_text, R.string.permission_manual_url
    )
    append("\n\n").append(context.getString(R.string.permission_footer))
}

/**
 * Appends a bullet with the text of [template], with its %1$s replaced by a link reading
 * [linkText] to [url]. Puts the link at the end if a translation lacks the placeholder.
 */
private fun SpannableStringBuilder.appendWithLink(
    context: Context,
    @StringRes template: Int,
    @StringRes linkText: Int,
    @StringRes url: Int,
) {
    val parts = context.getString(template).split("%1\$s", limit = 2)
    append("• ").append(parts[0])
    if (parts.size == 1) append(" ")
    inSpans(URLSpan(context.getString(url))) { append(context.getString(linkText)) }
    if (parts.size == 2) append(parts[1])
}
