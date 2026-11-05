package ru.karasevm.privatednstoggle.ui

import android.content.Context
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.dns.ProbeResult
import ru.karasevm.privatednstoggle.dns.ServerStatus

/** A sentence describing [status], for the details dialog and accessibility services */
fun describeStatus(context: Context, status: ServerStatus?): String = when (status) {
    null -> context.getString(R.string.status_not_tested)
    ServerStatus.Testing -> context.getString(R.string.status_testing)
    is ServerStatus.Tested -> when (val result = status.result) {
        is ProbeResult.Working -> context.getString(R.string.status_working, result.millis.toInt())
        is ProbeResult.Failed -> context.getString(
            when (result.reason) {
                ProbeResult.Reason.HOSTNAME_NOT_FOUND -> R.string.status_failed_hostname
                ProbeResult.Reason.UNREACHABLE -> R.string.status_failed_unreachable
                ProbeResult.Reason.TLS_FAILED -> R.string.status_failed_tls
                ProbeResult.Reason.CERTIFICATE_MISMATCH -> R.string.status_failed_certificate
                ProbeResult.Reason.NO_ANSWER -> R.string.status_failed_no_answer
            }
        )
    }
}
