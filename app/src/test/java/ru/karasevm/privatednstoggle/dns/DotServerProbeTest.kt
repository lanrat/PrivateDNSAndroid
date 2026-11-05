package ru.karasevm.privatednstoggle.dns

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Runs the probe against a local DNS-over-TLS server with a self-signed certificate for
 * "localhost", generated with keytool for the test.
 */
class DotServerProbeTest {

    private enum class Reply { ANSWER, WRONG_ID, NOTHING, REFUSED, NO_RECORDS, SLOW_DRIP }

    /** Serves one connection on a free port, answering the test query as [reply] says */
    private fun serveOnce(reply: Reply): Int {
        val server = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true) {
            server.use {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    requestedServerNames = (socket.session as ExtendedSSLSession).requestedServerNames
                    val input = DataInputStream(socket.inputStream)
                    val query = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    val output = DataOutputStream(socket.outputStream)
                    when (reply) {
                        Reply.NOTHING -> Thread.sleep(3_000)
                        // One byte at a time, each within the read timeout: only the overall
                        // deadline ends this
                        Reply.SLOW_DRIP -> runCatching {
                            output.writeShort(100)
                            repeat(100) {
                                Thread.sleep(300)
                                output.write(0)
                                output.flush()
                            }
                        }
                        else -> {
                            val answer = answerTo(query, reply)
                            output.writeShort(answer.size)
                            output.write(answer)
                            output.flush()
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    /** A response to [query]: one A record, or what [reply] asks for */
    private fun answerTo(query: ByteArray, reply: Reply): ByteArray {
        val answer = query.copyOf() + byteArrayOf(
            0xc0.toByte(), 0x0c, // name: pointer to the question
            0, 1, 0, 1, // A, IN
            0, 0, 0, 60, // TTL
            0, 4, 93.toByte(), 184.toByte(), 215.toByte(), 14, // 93.184.215.14
        )
        answer[2] = (answer[2].toInt() or 0x80).toByte()
        answer[7] = if (reply == Reply.NO_RECORDS) 0 else 1
        if (reply == Reply.REFUSED) answer[3] = 5
        if (reply == Reply.WRONG_ID) answer[1] = (answer[1] + 1).toByte()
        return answer
    }

    private fun probe(
        port: Int,
        socketFactory: SSLSocketFactory = trustingClientFactory,
        nameMatches: Boolean = true,
        hostname: String = "localhost",
        resolver: HostResolver = HostResolver { _, _ -> listOf(InetAddress.getLoopbackAddress()) },
    ) = runBlocking {
        DotServerProbe(
            resolver = resolver,
            timeoutMs = 1_000,
            connectTimeoutMs = 1_000,
            totalTimeoutMs = 3_000,
            port = port,
            sslSocketFactory = socketFactory,
            // The JVM has no default hostname check for raw sockets; the app uses Android's
            hostnameVerifier = { name, _ -> nameMatches && name == hostname.trimEnd('.') },
        ).probe(hostname)
    }

    @Test
    fun working() {
        val result = probe(serveOnce(Reply.ANSWER))
        assertTrue(result.toString(), result is ProbeResult.Working)
        // The server name was sent (SNI)
        assertEquals(listOf(SNIHostName("localhost")), requestedServerNames)
    }

    @Test
    fun untrustedCertificate() {
        val result = probe(serveOnce(Reply.ANSWER), socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory)
        assertEquals(ProbeResult.Reason.TLS_FAILED, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun certificateForAnotherName() {
        val result = probe(serveOnce(Reply.ANSWER), nameMatches = false)
        assertEquals(ProbeResult.Failed(ProbeResult.Reason.CERTIFICATE_MISMATCH), result)
    }

    @Test
    fun noAnswer() {
        val result = probe(serveOnce(Reply.NOTHING))
        assertEquals(ProbeResult.Reason.NO_ANSWER, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun answerToAnotherQuery() {
        val result = probe(serveOnce(Reply.WRONG_ID))
        assertEquals(ProbeResult.Reason.NO_ANSWER, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun errorResponse() {
        val result = probe(serveOnce(Reply.REFUSED))
        assertEquals(ProbeResult.Failed(ProbeResult.Reason.NO_ANSWER, "Response code 5"), result)
    }

    @Test
    fun answerWithoutRecords() {
        val result = probe(serveOnce(Reply.NO_RECORDS))
        assertEquals(ProbeResult.Reason.NO_ANSWER, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun slowServerHitsTheOverallDeadline() {
        val start = System.nanoTime()
        val result = probe(serveOnce(Reply.SLOW_DRIP))
        assertEquals(ProbeResult.Failed(ProbeResult.Reason.TIMED_OUT), result)
        assertTrue("took too long", System.nanoTime() - start < 5_000_000_000L)
    }

    @Test
    fun trailingDotIsAccepted() {
        // Used to crash the app: SNIHostName rejects a trailing dot
        val result = probe(serveOnce(Reply.ANSWER), hostname = "localhost.")
        assertTrue(result.toString(), result is ProbeResult.Working)
        assertEquals(listOf(SNIHostName("localhost")), requestedServerNames)
    }

    @Test
    fun nameThatIsNotAValidSniNameIsStillTested() {
        // "_" is allowed in DNS names but not in SNI; the test continues without SNI
        val result = probe(serveOnce(Reply.ANSWER), hostname = "my_dns.localhost")
        assertTrue(result.toString(), result is ProbeResult.Working)
        assertEquals(emptyList<Any>(), requestedServerNames.orEmpty())
    }

    @Test
    fun unexpectedExceptionsBecomeAFailure() {
        val result = probe(853, resolver = { _, _ -> throw IllegalStateException("boom") })
        assertEquals(ProbeResult.Reason.OTHER, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun interleavesAddressFamilies() {
        val v6a = InetAddress.getByName("2001:db8::1")
        val v6b = InetAddress.getByName("2001:db8::2")
        val v4a = InetAddress.getByName("192.0.2.1")
        val v4b = InetAddress.getByName("192.0.2.2")
        assertEquals(listOf(v6a, v4a, v6b, v4b), DotServerProbe.interleaveFamilies(listOf(v6a, v6b, v4a, v4b)))
        assertEquals(listOf(v4a, v6a, v4b), DotServerProbe.interleaveFamilies(listOf(v4a, v4b, v6a)))
    }

    @Test
    fun portClosed() {
        val port = ServerSocket(0).use { it.localPort }
        val result = probe(port)
        assertEquals(ProbeResult.Reason.UNREACHABLE, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun unknownHost() {
        val result = probe(853, resolver = { name, _ -> throw UnknownHostException(name) })
        assertEquals(ProbeResult.Reason.HOSTNAME_NOT_FOUND, (result as ProbeResult.Failed).reason)
    }

    companion object {
        private const val PASSWORD = "test-password"
        private lateinit var directory: File
        private lateinit var serverContext: SSLContext
        private lateinit var trustingClientFactory: SSLSocketFactory

        @Volatile
        private var requestedServerNames: List<Any>? = null

        @BeforeClass
        @JvmStatic
        fun createCertificate() {
            directory = Files.createTempDirectory("dot-probe-test").toFile()
            val keystoreFile = File(directory, "server.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-keystore", keystoreFile.path, "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, PASSWORD.toCharArray()) }
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, PASSWORD.toCharArray()) }.keyManagers
            serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }.trustManagers
            trustingClientFactory = SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory
        }

        @AfterClass
        @JvmStatic
        fun deleteCertificate() {
            directory.deleteRecursively()
        }
    }
}
