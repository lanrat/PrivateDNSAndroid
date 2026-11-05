package ru.karasevm.privatednstoggle.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test
    fun query_isAStandardRecursiveQueryForTheARecord() {
        val expected = byteArrayOf(
            0x12, 0x34, // id
            0x01, 0x00, // recursion desired
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 1 question
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
            0x00, 0x01, // A
            0x00, 0x01, // IN
        )
        assertArrayEquals(expected, DnsMessage.query(0x1234, "example.com"))
        assertArrayEquals(expected, DnsMessage.query(0x1234, "example.com."))
    }

    @Test
    fun isResponseTo_checksIdAndResponseFlag() {
        val response = DnsMessage.query(0xbeef, "example.com").also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertTrue(DnsMessage.isResponseTo(response, 0xbeef))
        // any response code counts, e.g. NXDOMAIN
        assertTrue(DnsMessage.isResponseTo(response.copyOf().also { it[3] = 0x03 }, 0xbeef))
        assertFalse(DnsMessage.isResponseTo(response, 0xbeee))
        assertFalse(DnsMessage.isResponseTo(DnsMessage.query(0xbeef, "example.com"), 0xbeef))
        assertFalse(DnsMessage.isResponseTo(ByteArray(5), 0))
    }
}
