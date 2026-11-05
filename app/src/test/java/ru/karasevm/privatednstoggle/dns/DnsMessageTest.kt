package ru.karasevm.privatednstoggle.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun addresses_readsAAndAaaaRecordsWithCompressedNames() {
        val response = DnsMessage.query(7, "dns.example", DnsMessage.TYPE_A) + byteArrayOf(
            0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 1,
            0xc0.toByte(), 0x0c, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 0x0c, // CNAME, skipped
            0xc0.toByte(), 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16,
        ) + java.net.InetAddress.getByName("2001:db8::1").address
        response[2] = 0x81.toByte()
        response[7] = 3
        assertEquals(
            listOf(java.net.InetAddress.getByName("192.0.2.1"), java.net.InetAddress.getByName("2001:db8::1")),
            DnsMessage.addresses(response, 7)
        )
        // Truncated or unrelated messages give nothing instead of throwing
        assertEquals(emptyList<java.net.InetAddress>(), DnsMessage.addresses(response.copyOf(20), 7))
        assertEquals(emptyList<java.net.InetAddress>(), DnsMessage.addresses(response, 8))
    }

    @Test
    fun answerProblem() {
        val ok = DnsMessage.query(1, "example.com").also { it[2] = 0x81.toByte(); it[7] = 1 }
        org.junit.Assert.assertNull(DnsMessage.answerProblem(ok, 1))
        org.junit.Assert.assertEquals("Not an answer to the test query", DnsMessage.answerProblem(ok, 2))
        org.junit.Assert.assertEquals("Response code 2", DnsMessage.answerProblem(ok.copyOf().also { it[3] = 2 }, 1))
        org.junit.Assert.assertEquals("No records in the answer", DnsMessage.answerProblem(ok.copyOf().also { it[7] = 0 }, 1))
    }
}
