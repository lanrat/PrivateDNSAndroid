package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/** The outcome of testing a Private DNS server */
sealed interface ProbeResult {
    /** The server answered a DNS query over TLS, after [millis] in total */
    data class Working(val millis: Long) : ProbeResult

    /** The server can't be used, see [reason]; [detail] is a technical message, if any */
    data class Failed(val reason: Reason, val detail: String? = null) : ProbeResult

    enum class Reason {
        /** The server's name couldn't be resolved */
        HOSTNAME_NOT_FOUND,

        /** No connection to port 853, e.g. the network blocks it */
        UNREACHABLE,

        /** The TLS handshake failed, e.g. an untrusted certificate */
        TLS_FAILED,

        /** The certificate is valid, but not for this name */
        CERTIFICATE_MISMATCH,

        /** Connected, but no usable answer to the test query */
        NO_ANSWER,

        /** The whole test took longer than allowed */
        TIMED_OUT,

        /** Anything else that went wrong */
        OTHER,
    }
}

/** Tests whether a Private DNS server works */
fun interface ServerProbe {
    /** Never throws; problems are reported as [ProbeResult.Failed] */
    suspend fun probe(hostname: String): ProbeResult
}

/**
 * Looks up the addresses of a host name. Blocking; [track] gets every socket it opens, so a
 * test that takes too long can close them. Throws [UnknownHostException] if there are none.
 */
fun interface HostResolver {
    fun resolve(hostname: String, track: (Closeable) -> Unit): List<InetAddress>

    companion object {
        /** The system resolver, which goes through Private DNS if that is on */
        val SYSTEM = HostResolver { hostname, _ -> InetAddress.getAllByName(hostname).toList() }
    }
}

/**
 * Tests a server the way Android validates a Private DNS hostname: look up its addresses,
 * connect to port 853, complete a TLS 1.2+ handshake with a certificate that is trusted and
 * valid for the hostname, and get an answer to one DNS query (RFC 7858): no error and at
 * least one record.
 *
 * It runs over the device's current network, so a network that blocks port 853 makes every
 * server fail, which is also what Private DNS itself would run into there.
 *
 * Each test takes at most [totalTimeoutMs]; after that, or when the caller is cancelled, its
 * sockets are closed, which ends the blocking work.
 */
class DotServerProbe(
    private val resolver: HostResolver = HostResolver.SYSTEM,
    private val timeoutMs: Int = 5_000,
    private val connectTimeoutMs: Int = 3_000,
    private val totalTimeoutMs: Long = 15_000,
    private val port: Int = 853,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : ServerProbe {

    // Not a child of the caller: a blocked lookup must not keep the caller waiting
    private val blockingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun probe(hostname: String): ProbeResult {
        // DNS names may end in a dot; TLS (SNI, certificates) uses them without
        val name = hostname.trim().trimEnd('.')
        val opened = ConcurrentLinkedQueue<Closeable>()
        val work = blockingScope.async { probeBlocking(name) { opened.add(it) } }
        return try {
            withTimeoutOrNull(totalTimeoutMs) { work.await() }
                ?: ProbeResult.Failed(ProbeResult.Reason.TIMED_OUT)
        } finally {
            if (!work.isCompleted) {
                work.cancel()
                opened.forEach { runCatching { it.close() } }
            }
        }
    }

    private fun probeBlocking(name: String, track: (Closeable) -> Unit): ProbeResult = try {
        probeSteps(name, track)
    } catch (e: Exception) {
        // Never let an unexpected exception escape: it would crash the app on every start
        ProbeResult.Failed(ProbeResult.Reason.OTHER, e.toString())
    }

    private fun probeSteps(name: String, track: (Closeable) -> Unit): ProbeResult {
        val start = System.nanoTime()
        val addresses = try {
            resolver.resolve(name, track)
        } catch (e: UnknownHostException) {
            return ProbeResult.Failed(ProbeResult.Reason.HOSTNAME_NOT_FOUND, e.message)
        }
        if (addresses.isEmpty()) return ProbeResult.Failed(ProbeResult.Reason.HOSTNAME_NOT_FOUND)

        val (socket, connectError) = connect(interleaveFamilies(addresses).take(MAX_ADDRESSES), track)
        if (socket == null) return ProbeResult.Failed(ProbeResult.Reason.UNREACHABLE, connectError)
        socket.use { raw ->
            val tls = (sslSocketFactory.createSocket(raw, name, port, true) as SSLSocket).also(track)
            tls.use {
                tls.soTimeout = timeoutMs
                // Like Android's Private DNS: TLS 1.2 or newer only
                tls.supportedProtocols.filter { it in MODERN_TLS }.takeIf { it.isNotEmpty() }?.let {
                    tls.enabledProtocols = it.toTypedArray()
                }
                // Server Name Indication, which DNS-over-TLS servers often need to pick a
                // certificate. Some valid DNS names (e.g. with "_") aren't valid SNI names.
                runCatching { SNIHostName(name) }.getOrNull()?.let { sni ->
                    tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(sni) }
                }
                try {
                    tls.startHandshake()
                } catch (e: SSLException) {
                    return ProbeResult.Failed(ProbeResult.Reason.TLS_FAILED, e.message)
                } catch (e: IOException) {
                    return ProbeResult.Failed(ProbeResult.Reason.UNREACHABLE, e.message)
                }
                // SSLSocket doesn't check the certificate's name itself
                if (!hostnameVerifier.verify(name, tls.session)) {
                    return ProbeResult.Failed(ProbeResult.Reason.CERTIFICATE_MISMATCH)
                }
                val problem = try {
                    query(tls)
                } catch (e: IOException) {
                    e.message ?: e.toString()
                }
                return if (problem == null) {
                    ProbeResult.Working((System.nanoTime() - start) / 1_000_000)
                } else {
                    ProbeResult.Failed(ProbeResult.Reason.NO_ANSWER, problem)
                }
            }
        }
    }

    /**
     * Connects to the first of [addresses] that accepts.
     * Returns the socket, or null and the last error. (Probes run concurrently, so no state.)
     */
    private fun connect(addresses: List<InetAddress>, track: (Closeable) -> Unit): Pair<Socket?, String?> {
        var error: String? = null
        for (address in addresses) {
            val socket = Socket().also(track)
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                return socket to null
            } catch (e: IOException) {
                socket.close()
                error = "$address: ${e.message}"
            }
        }
        return null to error
    }

    /**
     * Sends one query and reads the answer, both with the 2-byte length prefix of RFC 7858.
     * @return null if the answer is usable, otherwise what is wrong with it
     */
    private fun query(socket: SSLSocket): String? {
        val id = Random.nextInt(0x10000)
        val query = DnsMessage.query(id, TEST_NAME)
        val output = DataOutputStream(socket.outputStream)
        output.writeShort(query.size)
        output.write(query)
        output.flush()

        val input = DataInputStream(socket.inputStream)
        val answer = ByteArray(input.readUnsignedShort())
        input.readFully(answer)
        return DnsMessage.answerProblem(answer, id)
    }

    companion object {
        /** A name with A records that every resolver can answer and filters don't block */
        private const val TEST_NAME = "example.com"
        private const val MAX_ADDRESSES = 4
        private val MODERN_TLS = setOf("TLSv1.2", "TLSv1.3")

        /**
         * Orders [addresses] so IPv6 and IPv4 alternate, starting with the family of the first
         * one. With a broken IPv6 (or IPv4) path the other family is still tried early.
         */
        fun interleaveFamilies(addresses: List<InetAddress>): List<InetAddress> {
            val (v6, v4) = addresses.partition { it is Inet6Address }
            val (first, second) = if (addresses.firstOrNull() is Inet6Address) v6 to v4 else v4 to v6
            return (0 until maxOf(first.size, second.size)).flatMap {
                listOfNotNull(first.getOrNull(it), second.getOrNull(it))
            }
        }
    }
}

/** Minimal DNS wire format (RFC 1035), only what the probe and [PlainDns] need */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val FLAG_RECURSION_DESIRED = 0x0100
    private const val HEADER_SIZE = 12

    /** A standard recursive query for records of [type] (default A) of [name] with [id] */
    fun query(id: Int, name: String, type: Int = TYPE_A): ByteArray {
        val labels = name.trimEnd('.').split('.').map { it.toByteArray(Charsets.US_ASCII) }
        require(labels.all { it.size in 1..63 }) { "Invalid name: $name" }
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(FLAG_RECURSION_DESIRED)
            out.writeShort(1) // questions
            out.writeShort(0) // answers
            out.writeShort(0) // authority records
            out.writeShort(0) // additional records
            labels.forEach {
                out.writeByte(it.size)
                out.write(it)
            }
            out.writeByte(0)
            out.writeShort(type)
            out.writeShort(CLASS_IN)
        }
        return bytes.toByteArray()
    }

    /** Whether [message] is a response (any response code) to the query with [id] */
    fun isResponseTo(message: ByteArray, id: Int): Boolean {
        if (message.size < HEADER_SIZE) return false
        return u16(message, 0) == id && message[2].toInt() and 0x80 != 0
    }

    /**
     * Checks an answer to the probe's query like Android does: a response to [id], without
     * an error and with at least one answer record.
     * @return null if it is usable, otherwise what is wrong
     */
    fun answerProblem(message: ByteArray, id: Int): String? = when {
        !isResponseTo(message, id) -> "Not an answer to the test query"
        rcode(message) != 0 -> "Response code ${rcode(message)}"
        u16(message, 6) == 0 -> "No records in the answer"
        else -> null
    }

    /**
     * The IPv4 and IPv6 addresses in the answer section of a response to [id].
     * Empty for an error response or anything unexpected.
     */
    fun addresses(message: ByteArray, id: Int): List<InetAddress> {
        if (!isResponseTo(message, id) || rcode(message) != 0) return emptyList()
        return runCatching {
            var offset = HEADER_SIZE
            repeat(u16(message, 4)) { offset = skipName(message, offset) + 4 } // name, type, class
            val result = mutableListOf<InetAddress>()
            repeat(u16(message, 6)) {
                offset = skipName(message, offset)
                val type = u16(message, offset)
                val length = u16(message, offset + 8)
                val data = offset + 10
                if ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16)) {
                    result += InetAddress.getByAddress(message.copyOfRange(data, data + length))
                }
                offset = data + length
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun rcode(message: ByteArray) = message[3].toInt() and 0x0f

    private fun u16(message: ByteArray, offset: Int): Int {
        if (offset + 1 >= message.size) throw IndexOutOfBoundsException("DNS message too short")
        return ((message[offset].toInt() and 0xff) shl 8) or (message[offset + 1].toInt() and 0xff)
    }

    /** Skips a possibly compressed name starting at [start], returns the offset after it */
    private fun skipName(message: ByteArray, start: Int): Int {
        var offset = start
        while (true) {
            val length = message[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                length and 0xc0 == 0xc0 -> return offset + 2 // pointer, ends the name
                else -> offset += length + 1
            }
        }
    }
}

/**
 * Looks up host names with plain DNS queries (UDP port 53) to given servers, bypassing the
 * system resolver and so Private DNS. Android resolves the Private DNS hostname the same way,
 * so a broken or filtering active Private DNS server doesn't affect testing the others.
 */
object PlainDns {
    /**
     * Queries A and AAAA records of [hostname] from [servers] (at most two, in order) and
     * returns the addresses of the first server that answers; empty if none does.
     */
    fun lookup(
        hostname: String,
        servers: List<InetAddress>,
        timeoutMs: Int,
        track: (Closeable) -> Unit,
        port: Int = 53,
    ): List<InetAddress> {
        for (server in servers.take(2)) {
            val addresses = runCatching { lookupAt(hostname, server, port, timeoutMs, track) }
                .getOrDefault(emptyList())
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    }

    private fun lookupAt(
        hostname: String,
        server: InetAddress,
        port: Int,
        timeoutMs: Int,
        track: (Closeable) -> Unit,
    ): List<InetAddress> {
        java.net.DatagramSocket().also(track).use { socket ->
            socket.soTimeout = timeoutMs
            val ids = mapOf(
                DnsMessage.TYPE_AAAA to Random.nextInt(0x10000),
                DnsMessage.TYPE_A to Random.nextInt(0x10000),
            )
            ids.forEach { (type, id) ->
                val query = DnsMessage.query(id, hostname, type)
                socket.send(java.net.DatagramPacket(query, query.size, server, port))
            }
            val answers = mutableMapOf<Int, List<InetAddress>>()
            val buffer = ByteArray(1232)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (answers.size < ids.size && System.nanoTime() < deadline) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
                if (packet.address != server) continue
                val message = buffer.copyOf(packet.length)
                ids.forEach { (type, id) ->
                    if (DnsMessage.isResponseTo(message, id)) answers[type] = DnsMessage.addresses(message, id)
                }
            }
            // IPv6 first, like getaddrinfo usually orders them
            return answers[DnsMessage.TYPE_AAAA].orEmpty() + answers[DnsMessage.TYPE_A].orEmpty()
        }
    }
}
