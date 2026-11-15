package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RelayPairingTest {
    private lateinit var dir: File
    private lateinit var store: RelayTokenStore
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        dir = Files.createTempDirectory("relay-pair").toFile()
        store = RelayTokenStore(dir, SoftCipher())
    }

    @After fun tearDown() {
        closers.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** Expected values computed with node:crypto (pbkdf2Sync + createHmac), the relay's own implementation language. */
    @Test fun proofsMatchTheNodeVectors() {
        val nonce = RelayPairing.unhex("000102030405060708090a0b0c0d0e0f")
        val fp = "b16c0a29e9a2a151c58dff4c178fce3a961e2358819dc39f51d49f59b0d9df95"
        val key = RelayPairing.deriveKey("7G3KQ9XW2M", nonce)
        assertEquals("1f700826a14cbb75a774d263d2df138270cbe01aa6d50440979d1c3258299e75", RelayPairing.hex(key))
        assertEquals("4edda7e26a0685a64ad8f4701524364fab63002af28ab145d1e9636bf2dcaa27", RelayPairing.clientProof(key, fp))
        assertEquals("d551c213bd6f3bed5c2b05ca246c9dcb4c091c81223f0fa297204ba17730f733", RelayPairing.serverProof(key, fp))
    }

    /** 契约向量：中继、Kotlin 共用 `contract/samples/pair-vector.json`。 */
    @Test fun proofsMatchTheContractVector() {
        val f = File("../../../tooling/dsh-relay/contract/samples/pair-vector.json")
        org.junit.Assume.assumeTrue("contract sample missing", f.isFile)
        @Suppress("UNCHECKED_CAST")
        val v = Json.parse(f.readText()) as Map<String, Any?>
        assertEquals(v["normalizedCode"], RelayPairing.normalizeCode(v["code"] as String))
        val key = RelayPairing.deriveKey(v["normalizedCode"] as String, RelayPairing.unhex(v["nonceHex"] as String))
        assertEquals(v["keyHex"], RelayPairing.hex(key))
        assertEquals(v["proofHex"], RelayPairing.clientProof(key, v["seenFp"] as String))
        assertEquals(v["serverProofHex"], RelayPairing.serverProof(key, v["seenFp"] as String))
    }

    /** F4：存不下就在花掉一次性配对码之前失败，Mac 上不留令牌。 */
    @Test fun aKeystoreThatCannotSealFailsBeforeTheRelayIsAsked() {
        val r = relay("7G3KQ9XW2M")
        val cipher = SoftCipher().also { it.failEncrypt = true }
        val bad = RelayTokenStore(dir, cipher)
        val out = RelayPairing.pair(r.addr, "7G3KQ9XW2M", "DSH Go", bad, client())
        assertEquals(RelayCodes.STORE_FAILED, (out as RelayPairing.Outcome.Failed).error.code)
        assertTrue("no request reached the relay", r.seen.isEmpty())
    }

    @Test fun theCodeIsNormalizedTheWayTheMacPrintsIt() {
        assertEquals("7G3KQ9XW2M", RelayPairing.normalizeCode(" 7g3kq-9xw2m "))
        assertEquals("0011", RelayPairing.normalizeCode("OoIl"))
    }

    /** The relay side of §5.5, written from the spec rather than from the client. */
    private fun relay(code: String, honest: Boolean = true, tokenOut: String = "TOKEN-XYZ"): FakeRelay {
        lateinit var r: FakeRelay
        r = FakeRelay { s, ex ->
            @Suppress("UNCHECKED_CAST")
            val b = Json.parse(s.body) as Map<String, Any?>
            val key = RelayPairing.deriveKey(code, RelayPairing.unhex(b["nonce"] as String))
            val realFp = r.fp
            val good = b["seenFp"] == realFp && b["proof"] == RelayPairing.clientProof(key, realFp)
            if (!good) FakeRelay.reply(ex, 401, """{"ok":false,"error":{"code":"unauthorized","message":"bad code"}}""")
            else {
                val proof = if (honest) RelayPairing.serverProof(key, realFp) else "0".repeat(64)
                FakeRelay.reply(ex, 200, """{"ok":true,"token":"$tokenOut","serverProof":"$proof"}""")
            }
        }
        return r.also { closers += it }
    }

    private fun client() = RelayClient(store, pause = {})

    @Test fun rightCodePairsAndSavesFingerprintAndToken() {
        val r = relay("7G3KQ9XW2M")
        val out = RelayPairing.pair(r.addr, "7g3kq-9xw2m", "DSH Go", store, client())
        val cfg = (out as RelayPairing.Outcome.Paired).config
        assertEquals(r.fp, cfg.fp)
        assertEquals("TOKEN-XYZ", store.load()?.token)
        assertEquals(r.fp, store.load()?.fp)
        // The code itself never goes on the wire.
        assertFalse(r.seen.single().body.contains("7G3KQ9XW2M"))
        assertNull("pairing is the one unauthenticated call", r.seen.single().headers["authorization"])
    }

    @Test fun wrongCodeIsRefusedAndNothingIsSaved() {
        val r = relay("7G3KQ9XW2M")
        val out = RelayPairing.pair(r.addr, "AAAAAAAAAA", "DSH Go", store, client())
        assertEquals(RelayCodes.UNAUTHORIZED, (out as RelayPairing.Outcome.Failed).error.code)
        assertNull(store.load())
    }

    @Test fun aRelayThatCannotProveItKnowsTheCodeIsRejectedAndNothingIsSaved() {
        val r = relay("7G3KQ9XW2M", honest = false)
        val out = RelayPairing.pair(r.addr, "7G3KQ9XW2M", "DSH Go", store, client())
        assertEquals(RelayCodes.PIN_MISMATCH, (out as RelayPairing.Outcome.Failed).error.code)
        assertNull(store.load())
    }

    @Test fun aManInTheMiddleWhoSwapsTheCertificateFailsTheRelaysCheck() {
        // 中间人：自己一张证书，把请求原样转给真中继。真中继看到的 seenFp 是中间人的指纹，对不上它自己的指纹。
        val real = relay("7G3KQ9XW2M")
        val mitm = FakeRelay { s, ex ->
            val c = java.net.URL("https://${real.addr}/v1/pair").openConnection() as javax.net.ssl.HttpsURLConnection
            c.sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                    override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
                }), null)
            }.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            c.doOutput = true
            c.requestMethod = "POST"
            c.outputStream.use { it.write(s.body.toByteArray()) }
            val status = c.responseCode
            val body = (if (status >= 400) c.errorStream else c.inputStream).readBytes().toString(Charsets.UTF_8)
            FakeRelay.reply(ex, status, body)
        }.also { closers += it }
        val out = RelayPairing.pair(mitm.addr, "7G3KQ9XW2M", "DSH Go", store, client())
        assertTrue(out is RelayPairing.Outcome.Failed)
        assertNull(store.load())
    }

    @Test fun nothingListeningIsUnreachable() {
        val r = relay("7G3KQ9XW2M")
        val addr = r.addr
        r.close()
        val out = RelayPairing.pair(addr, "7G3KQ9XW2M", "DSH Go", store, client(), connectMs = 500)
        assertEquals(RelayCodes.UNREACHABLE, (out as RelayPairing.Outcome.Failed).error.code)
    }
}
