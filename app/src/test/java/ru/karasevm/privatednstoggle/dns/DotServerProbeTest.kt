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

    private enum class Reply { ANSWER, WRONG_ID, NOTHING }

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
                    when (reply) {
                        Reply.NOTHING -> Thread.sleep(2_000)
                        Reply.ANSWER, Reply.WRONG_ID -> {
                            val answer = query.copyOf()
                            answer[2] = (answer[2].toInt() or 0x80).toByte()
                            if (reply == Reply.WRONG_ID) answer[1] = (answer[1] + 1).toByte()
                            DataOutputStream(socket.outputStream).apply {
                                writeShort(answer.size)
                                write(answer)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    private fun probe(
        port: Int,
        socketFactory: SSLSocketFactory = trustingClientFactory,
        nameMatches: Boolean = true,
        resolve: (String) -> List<InetAddress> = { listOf(InetAddress.getLoopbackAddress()) },
    ) = runBlocking {
        DotServerProbe(
            timeoutMs = 1_000,
            port = port,
            sslSocketFactory = socketFactory,
            // The JVM has no default hostname check for raw sockets; the app uses Android's
            hostnameVerifier = { hostname, _ -> nameMatches && hostname == "localhost" },
            resolve = resolve,
        ).probe("localhost")
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
    fun portClosed() {
        val port = ServerSocket(0).use { it.localPort }
        val result = probe(port)
        assertEquals(ProbeResult.Reason.UNREACHABLE, (result as ProbeResult.Failed).reason)
    }

    @Test
    fun unknownHost() {
        val result = probe(853, resolve = { throw UnknownHostException(it) })
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
