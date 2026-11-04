package ru.karasevm.privatednstoggle.util

import com.google.common.net.InternetDomainName

object ServerValidation {
    /**
     * Whether [server] can be used as a Private DNS hostname. Android only accepts host names
     * there, not IP addresses or URLs.
     */
    fun isValidHostname(server: String): Boolean =
        server.isNotEmpty() && server == server.trim() && InternetDomainName.isValid(server)
}
