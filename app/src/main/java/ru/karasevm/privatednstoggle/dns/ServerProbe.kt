package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
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

        /** Connected, but no valid answer to the test query */
        NO_ANSWER,
    }
}

/** Tests whether a Private DNS server works */
fun interface ServerProbe {
    suspend fun probe(hostname: String): ProbeResult
}

/**
 * Tests a server the way Android validates a Private DNS hostname: connect to port 853,
 * complete a TLS handshake with a certificate that is trusted and valid for the hostname, and
 * get an answer to one DNS query (RFC 7858). Any well-formed answer counts, also NXDOMAIN or a
 * filtered result, since that still means the server is usable.
 *
 * It runs over the device's current network, so a network that blocks port 853 makes every
 * server fail, which is also what Private DNS itself would run into there.
 */
class DotServerProbe(
    private val timeoutMs: Int = 5_000,
    private val port: Int = 853,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : ServerProbe {

    override suspend fun probe(hostname: String): ProbeResult =
        withContext(Dispatchers.IO) { probeBlocking(hostname) }

    private fun probeBlocking(hostname: String): ProbeResult {
        val start = System.nanoTime()
        val addresses = try {
            resolve(hostname)
        } catch (e: UnknownHostException) {
            return ProbeResult.Failed(ProbeResult.Reason.HOSTNAME_NOT_FOUND, e.message)
        }
        if (addresses.isEmpty()) return ProbeResult.Failed(ProbeResult.Reason.HOSTNAME_NOT_FOUND)

        val (socket, connectError) = connect(addresses)
        if (socket == null) return ProbeResult.Failed(ProbeResult.Reason.UNREACHABLE, connectError)
        socket.use { raw ->
            val tls = sslSocketFactory.createSocket(raw, hostname, port, true) as SSLSocket
            tls.use {
                tls.soTimeout = timeoutMs
                // Server Name Indication, which DNS-over-TLS servers often need to pick a certificate
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(hostname)) }
                try {
                    tls.startHandshake()
                } catch (e: SSLException) {
                    return ProbeResult.Failed(ProbeResult.Reason.TLS_FAILED, e.message)
                } catch (e: IOException) {
                    return ProbeResult.Failed(ProbeResult.Reason.UNREACHABLE, e.message)
                }
                // SSLSocket doesn't check the certificate's name itself
                if (!hostnameVerifier.verify(hostname, tls.session)) {
                    return ProbeResult.Failed(ProbeResult.Reason.CERTIFICATE_MISMATCH)
                }
                return try {
                    query(tls)
                    ProbeResult.Working((System.nanoTime() - start) / 1_000_000)
                } catch (e: IOException) {
                    ProbeResult.Failed(ProbeResult.Reason.NO_ANSWER, e.message)
                }
            }
        }
    }

    /**
     * Connects to the first of [addresses] that accepts, trying at most two.
     * Returns the socket, or null and the last error. (The probe runs concurrently, so no state.)
     */
    private fun connect(addresses: List<InetAddress>): Pair<Socket?, String?> {
        var error: String? = null
        for (address in addresses.take(2)) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return socket to null
            } catch (e: IOException) {
                socket.close()
                error = e.message
            }
        }
        return null to error
    }

    /** Sends one query and reads the answer, both with the 2-byte length prefix of RFC 7858 */
    private fun query(socket: SSLSocket) {
        val id = Random.nextInt(0x10000)
        val query = DnsMessage.query(id, TEST_NAME)
        val output = DataOutputStream(socket.outputStream)
        output.writeShort(query.size)
        output.write(query)
        output.flush()

        val input = DataInputStream(socket.inputStream)
        val answer = ByteArray(input.readUnsignedShort())
        input.readFully(answer)
        if (!DnsMessage.isResponseTo(answer, id)) throw IOException("Not an answer to the test query")
    }

    private companion object {
        /** A name every resolver can answer and filtering servers don't block */
        const val TEST_NAME = "example.com"
    }
}

/** Minimal DNS wire format (RFC 1035), only what the probe needs */
object DnsMessage {
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val FLAG_RECURSION_DESIRED = 0x0100

    /** A standard query for the A record of [name] with the given [id] */
    fun query(id: Int, name: String): ByteArray {
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
            out.writeShort(TYPE_A)
            out.writeShort(CLASS_IN)
        }
        return bytes.toByteArray()
    }

    /** Whether [message] is a response (any response code) to the query with [id] */
    fun isResponseTo(message: ByteArray, id: Int): Boolean {
        if (message.size < 12) return false
        val messageId = ((message[0].toInt() and 0xff) shl 8) or (message[1].toInt() and 0xff)
        val isResponse = message[2].toInt() and 0x80 != 0
        return messageId == id && isResponse
    }
}
