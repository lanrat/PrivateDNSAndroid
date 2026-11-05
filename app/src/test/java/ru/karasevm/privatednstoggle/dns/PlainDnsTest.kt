package ru.karasevm.privatednstoggle.dns

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class PlainDnsTest {

    private val loopback = InetAddress.getLoopbackAddress()

    /** Answers the A and AAAA queries for any name, unless [rcode] is set */
    private fun serveTwice(rcode: Int = 0): Int {
        val socket = DatagramSocket(0, loopback)
        thread(isDaemon = true) {
            socket.use {
                repeat(2) {
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val query = buffer.copyOf(packet.length)
                    val isAaaa = query[query.size - 3].toInt() == DnsMessage.TYPE_AAAA
                    val data = if (isAaaa) InetAddress.getByName("2001:db8::53").address else byteArrayOf(192.toByte(), 0, 2, 53)
                    val answer = query + byteArrayOf(0xc0.toByte(), 0x0c, 0, query[query.size - 3], 0, 1, 0, 0, 0, 60, 0, data.size.toByte()) + data
                    answer[2] = (answer[2].toInt() or 0x80).toByte()
                    answer[3] = rcode.toByte()
                    answer[7] = 1
                    socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                }
            }
        }
        return socket.localPort
    }

    @Test
    fun returnsIpv6ThenIpv4Addresses() {
        val port = serveTwice()
        assertEquals(
            listOf(InetAddress.getByName("2001:db8::53"), InetAddress.getByName("192.0.2.53")),
            PlainDns.lookup("dns.example", listOf(loopback), 1_000, {}, port)
        )
    }

    @Test
    fun errorResponsesGiveNoAddresses() {
        val port = serveTwice(rcode = 3) // NXDOMAIN
        assertEquals(emptyList<InetAddress>(), PlainDns.lookup("dns.example", listOf(loopback), 1_000, {}, port))
    }

    @Test
    fun noServerAnswering() {
        val port = DatagramSocket(0, loopback).use { it.localPort }
        assertEquals(emptyList<InetAddress>(), PlainDns.lookup("dns.example", listOf(loopback), 300, {}, port))
        assertEquals(emptyList<InetAddress>(), PlainDns.lookup("dns.example", emptyList(), 300, {}, port))
    }
}
