package ru.karasevm.privatednstoggle.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerValidationTest {

    private fun valid(server: String) = assertTrue(server, ServerValidation.isValidHostname(server))
    private fun invalid(server: String) = assertFalse(server, ServerValidation.isValidHostname(server))

    @Test
    fun acceptsHostNames() {
        valid("dns.google")
        valid("one.one.one.one")
        valid("1dot1dot1dot1.cloudflare-dns.com")
        valid("DNS.Quad9.net")
        valid("dns.google.")
        valid("abc123.dns.nextdns.io")
        valid("my_server.example.com")
        valid("localhost")
        valid("a".repeat(63) + ".com")
        valid(List(3) { "a".repeat(63) }.joinToString(".") + "." + "a".repeat(61)) // 253 characters
    }

    @Test
    fun rejectsOtherInput() {
        invalid("")
        invalid(".")
        invalid(" dns.google")
        invalid("dns.google ")
        invalid("dns google")
        invalid("dns..google")
        invalid(".dns.google")
        invalid("dns.google..")
        invalid("-dns.google")
        invalid("dns-.google")
        invalid("_dns.google")
        invalid("dns.google_")
        invalid("a".repeat(64) + ".com")
        invalid(List(3) { "a".repeat(63) }.joinToString(".") + "." + "a".repeat(62)) // 254 characters
        invalid("https://dns.google")
        invalid("dns.google/dns-query")
        invalid("dns.google:853")
        invalid("bücher.de")
    }

    @Test
    fun rejectsIpAddresses() {
        invalid("1.1.1.1")
        invalid("8.8.8.8.")
        invalid("2606:4700:4700::1111")
        invalid("[2606:4700:4700::1111]")
        invalid("example.123")
    }
}
