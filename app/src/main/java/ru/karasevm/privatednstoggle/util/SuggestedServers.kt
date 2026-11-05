package ru.karasevm.privatednstoggle.util

/**
 * Well-known public Private DNS (DNS-over-TLS) providers offered in the "Add server" dialog.
 * Nothing is saved until the user adds one; the app ships with no servers.
 */
object SuggestedServers {

    data class Suggestion(val label: String, val server: String)

    val ALL = listOf(
        Suggestion("Cloudflare", "one.one.one.one"),
        Suggestion("Google", "dns.google"),
        Suggestion("Quad9", "dns.quad9.net"),
        Suggestion("AdGuard", "dns.adguard-dns.com"),
        Suggestion("Mullvad", "dns.mullvad.net"),
    )

    /** The suggestions not in [saved], compared ignoring case like the database does */
    fun notSaved(saved: Collection<String>): List<Suggestion> {
        val savedLower = saved.mapTo(HashSet()) { it.lowercase() }
        return ALL.filter { it.server.lowercase() !in savedLower }
    }
}
