package io.github.ccch1mneyyy.dsh_app.phone.relay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.security.auth.x500.X500Principal
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A minimal HTTPS/1.1 server (one request per connection, `Connection: close`) over a JDK
 * [SSLServerSocket] with a fresh self-signed P-256 certificate on a random loopback port.
 * Hand-rolled because the app's unit-test compile classpath is android.jar, which has no
 * `com.sun.net.httpserver`; the TLS stack under test is still the JDK's.
 */
class FakeRelayServer(private val handler: (method: String, path: String, headers: Map<String, String>, body: String, ex: Exchange) -> Unit) : AutoCloseable {
    class Exchange internal constructor(private val socket: Socket) {
        fun reply(status: Int, json: String) {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            socket.close()
        }

        /** Hangs up without an answer. */
        fun hangUp() = socket.close()
    }

    private val server: SSLServerSocket
    private val der: ByteArray

    init {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val subject = X500Principal("CN=dsh-relay-test")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), subject, pair.public)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
        der = cert.encoded
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("k", pair.private, "pw".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "pw".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
        Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread({ serve(s) }).apply { isDaemon = true }.start()
            }
        }).apply { isDaemon = true }.start()
    }

    fun certDer(): ByteArray = der

    fun addr(): String = "127.0.0.1:${server.localPort}"

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 5_000
            val input = BufferedInputStream(s.getInputStream())
            val head = ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = input.read()
                if (b < 0) { s.close(); return }
                head.write(b)
                tail = ((tail shl 8) or b) and 0x7fffffff
                if (head.size() >= 4 && head.toByteArray().let { h -> h.size >= 4 && String(h, h.size - 4, 4) == "\r\n\r\n" }) break
            }
            val lines = head.toString("UTF-8").split("\r\n")
            val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
            val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            handler(method, path, headers, String(body, Charsets.UTF_8), Exchange(s))
        } catch (_: Exception) {
            runCatching { s.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
