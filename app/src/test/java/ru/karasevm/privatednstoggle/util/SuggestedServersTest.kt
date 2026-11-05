package ru.karasevm.privatednstoggle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestedServersTest {

    @Test
    fun suggestionsAreValidAndUnique() {
        for (suggestion in SuggestedServers.ALL) {
            assertTrue(suggestion.server, ServerValidation.isValidHostname(suggestion.server))
            assertTrue(suggestion.label.isNotBlank())
        }
        assertEquals(SuggestedServers.ALL.size, SuggestedServers.ALL.map { it.server.lowercase() }.toSet().size)
        assertEquals(SuggestedServers.ALL.size, SuggestedServers.ALL.map { it.label }.toSet().size)
    }

    @Test
    fun savedServersAreLeftOutIgnoringCase() {
        val left = SuggestedServers.notSaved(listOf("DNS.Google", "example.com"))
        assertEquals(SuggestedServers.ALL.filter { it.server != "dns.google" }, left)
    }

    @Test
    fun allAreOfferedWithNothingSaved() {
        assertEquals(SuggestedServers.ALL, SuggestedServers.notSaved(emptyList()))
    }
}
