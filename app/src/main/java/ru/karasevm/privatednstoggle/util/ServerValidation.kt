package ru.karasevm.privatednstoggle.util

object ServerValidation {
    private const val MAX_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    // Letters, digits, '-' and '_', not starting or ending with '-' or '_'
    private val LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?")

    /**
     * Whether [server] can be used as a Private DNS hostname. Android only accepts host names
     * there, of ASCII letters, digits, '-', '_' and '.', and not IP addresses or URLs.
     *
     * The name is made of dot-separated labels of up to 63 characters, at most 253 characters
     * in all, optionally with a trailing dot. The last label must not start with a digit,
     * which also rules out IPv4 addresses.
     */
    fun isValidHostname(server: String): Boolean {
        val name = server.removeSuffix(".")
        if (name.isEmpty() || name.length > MAX_LENGTH) return false
        val labels = name.split('.')
        return labels.all { it.length <= MAX_LABEL_LENGTH && LABEL.matches(it) } &&
            !labels.last().first().isDigit()
    }
}
