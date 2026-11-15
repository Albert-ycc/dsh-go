package io.github.ccch1mneyyy.dsh_app.phone.relay

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RelayClientTest {
    private lateinit var dir: File
    private lateinit var store: RelayTokenStore
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        dir = Files.createTempDirectory("relay-test").toFile()
        store = RelayTokenStore(dir, SoftCipher())
    }

    @After fun tearDown() {
        closers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun relay(handler: (Seen, FakeRelayServer.Exchange) -> Unit) = FakeRelay(handler).also { closers += it }

    private fun client(r: FakeRelay, fp: String = r.fp, timeouts: RelayTimeouts = RelayTimeouts(connectMs = 2_000, otherMs = 1_000, actMs = 1_000, observeMs = 1_000)): RelayClient {
        store.save(RelayConfig(r.addr, fp, "tok-123"))
        return RelayClient(store, taskId = { 41 }, timeouts = timeouts, pause = {})
    }

    private fun err(r: RelayResult<*>): RelayError = (r as RelayResult.Err).error

    @Test fun sendsTokenAndTheHeadersTheRelayRequires() {
        val r = relay { _, ex -> FakeRelay.reply(ex, 200, """{"ok":true,"halted":false,"device":{"state":"ok","via":"usb","model":"VDE-AL00","locked":false},"queue":{"depth":0},"version":"1"}""") }
        val d = (client(r).device() as RelayResult.Ok).value
        assertEquals("VDE-AL00", d.device?.model)
        assertEquals(false, d.device?.locked)
        val s = r.seen.single()
        assertEquals("GET", s.method)
        assertEquals("/v1/device", s.path)
        assertEquals("Bearer tok-123", s.headers["authorization"])
        assertEquals("1", s.headers["x-dsh-relay"])
        assertEquals("41", s.headers["x-dsh-task"])
        assertTrue(s.headers["x-request-id"]!!.length >= 32)
    }

    @Test fun aCertificateThatIsNotThePinnedOneNeverGetsTheRequest() {
        val r = relay { _, ex -> FakeRelay.reply(ex, 200, """{"ok":true}""") }
        val e = err(client(r, fp = "00".repeat(32)).observe(false))
        assertEquals(RelayCodes.PIN_MISMATCH, e.code)
        assertTrue("the handshake must fail before any request is sent", r.seen.isEmpty())
    }

    @Test fun errorCodesFromTheRelayMapToTheirOwnCodes() {
        val cases = listOf(
            401 to RelayCodes.UNAUTHORIZED, 403 to RelayCodes.FORBIDDEN_SOURCE, 409 to RelayCodes.CANCELLED, 422 to RelayCodes.BAD_ARGS,
            423 to RelayCodes.HALTED, 424 to RelayCodes.COMMAND_FAILED, 429 to RelayCodes.BUSY, 503 to RelayCodes.DEVICE_OFFLINE,
            504 to RelayCodes.DEVICE_TIMEOUT, 500 to RelayCodes.INTERNAL,
        )
        for ((status, code) in cases) {
            val r = relay { _, ex -> FakeRelay.reply(ex, status, """{"ok":false,"error":{"code":"$code","message":"m-$status","retryable":true,"hint":"h"}}""") }
            val e = err(client(r).act(mapOf("op" to "click", "x" to 1, "y" to 2)))
            assertEquals(code, e.code)
            assertEquals("m-$status", e.message)
            assertTrue(e.retryable)
            assertEquals("h", e.hint)
            assertEquals(status, e.http)
            assertFalse("every code has a sentence for the model", RelayMessages.forModel(e).isBlank())
        }
    }

    @Test fun anAnswerWithoutAReadableBodyFallsBackOnTheStatus() {
        val r = relay { _, ex -> FakeRelay.reply(ex, 423, "not json") }
        assertEquals(RelayCodes.HALTED, err(client(r).launch("com.huawei.hmos.settings")).code)
    }

    @Test fun deviceOfflineAndLockedHaveDistinctSentencesForTheModel() {
        val offline = RelayMessages.forModel(RelayError(RelayCodes.DEVICE_OFFLINE, ""))
        val locked = RelayMessages.forModel(RelayError(RelayCodes.DEVICE_LOCKED, ""))
        val halted = RelayMessages.forModel(RelayError(RelayCodes.HALTED, ""))
        assertEquals(3, setOf(offline, locked, halted).size)
        assertTrue(halted.contains("dsh-relay resume"))
    }

    @Test fun aLostAnswerIsResentOnceWithTheSameRequestId() {
        var n = 0
        val r = relay { _, ex ->
            if (++n == 1) ex.hangUp() // 请求到了，应答没回来
            else FakeRelay.reply(ex, 200, """{"ok":true,"op":"click","steps":[],"tookMs":3}""")
        }
        val res = client(r).act(mapOf("op" to "click", "x" to 10, "y" to 20))
        assertTrue(res is RelayResult.Ok)
        assertEquals(2, r.seen.size)
        assertEquals(r.seen[0].headers["x-request-id"], r.seen[1].headers["x-request-id"])
        assertEquals(r.seen[0].body, r.seen[1].body)
    }

    @Test fun noAnswerTwiceIsUnreachableAndEveryCopyCarriesTheSameId() {
        val r = relay { _, ex -> ex.hangUp() }
        val e = err(client(r).act(mapOf("op" to "key", "name" to "back")))
        assertEquals(RelayCodes.UNREACHABLE, e.code)
        // 我们发两次；JDK 的 HttpURLConnection 对断开的 POST 还会自己再补一次，所以线上最多四份。
        assertTrue(r.seen.size in 2..4)
        assertEquals(1, r.seen.map { it.headers["x-request-id"] }.toSet().size)
    }

    @Test fun aRelayThatIsNotThereIsUnreachable() {
        val r = relay { _, ex -> FakeRelay.reply(ex, 200, "{}") }
        val c = client(r)
        r.close()
        assertEquals(RelayCodes.UNREACHABLE, err(c.observe(false)).code)
    }

    @Test fun inProgressIsAskedAgainWithTheSameIdUntilItFinishes() {
        var n = 0
        val r = relay { _, ex ->
            if (++n < 3) FakeRelay.reply(ex, 202, """{"ok":false,"status":"in_progress"}""")
            else FakeRelay.reply(ex, 200, """{"ok":true,"op":"swipe"}""")
        }
        assertTrue(client(r).act(mapOf("op" to "swipe")) is RelayResult.Ok)
        assertEquals(3, r.seen.size)
        assertEquals(1, r.seen.map { it.headers["x-request-id"] }.toSet().size)
    }

    @Test fun deviceAnswersAreCachedButErrorsOnlyBriefly() {
        var now = 1_000L
        var calls = 0
        val r = relay { _, ex -> calls++; FakeRelay.reply(ex, 200, """{"ok":true,"halted":false,"device":{"state":"ok","locked":true}}""") }
        store.save(RelayConfig(r.addr, r.fp, "t"))
        val c = RelayClient(store, clock = { now }, pause = {})
        c.device(); c.device()
        assertEquals(1, calls)
        assertNull("the relay's cached lock flag is not a reading of the screen", c.cachedLocked())
        now += 6_000
        c.device()
        assertEquals(2, calls)
    }

    /** F2：锁屏状态只认最近一次 observe 回的 device.locked，15 秒内有效。 */
    @Test fun cachedLockedIsTheLastObserveAnswerAndExpiresAfterFifteenSeconds() {
        var now = 1_000L
        var locked = true
        val r = relay { _, ex -> FakeRelay.reply(ex, 200, """{"ok":true,"device":{"locked":$locked},"windows":[]}""") }
        store.save(RelayConfig(r.addr, r.fp, "t"))
        val c = RelayClient(store, clock = { now }, pause = {})
        assertNull(c.cachedLocked())
        c.observe(false)
        assertEquals(true, c.cachedLocked())
        locked = false
        now += 5_000
        c.observe(false)
        assertEquals(false, c.cachedLocked())
        now += 16_000
        assertNull("an old reading says nothing about the lock", c.cachedLocked())
    }

    @Test fun observeWaitsLongerThanTheRelaysOwnReconnectRetry() {
        assertTrue(RelayTimeouts().observeMs >= 18_000)
    }

    @Test fun aTreeNestedPastTheJsonParsersLimitIsABadResponseNotACrash() {
        var tree = """{"bounds":[0,0,1,1]}"""
        repeat(40) { tree = """{"bounds":[0,0,1,1],"children":[$tree]}""" }
        val r = relay { _, ex -> FakeRelay.reply(ex, 200, """{"ok":true,"windows":[{"root":$tree}]}""") }
        assertEquals(RelayCodes.BAD_RESPONSE, err(client(r).observe(false)).code)
    }

    @Test fun withoutAPairingNothingGoesOut() {
        val c = RelayClient(RelayTokenStore(File(dir, "empty"), SoftCipher()))
        assertFalse(c.configured())
        assertEquals(RelayCodes.UNCONFIGURED, err(c.device()).code)
    }

    @Test fun tokenFileIsPrivateAndTheTokenIsNotInToString() {
        val cfg = RelayConfig("10.0.0.2:8731", "ab", "secret-token")
        store.save(cfg)
        val f = File(dir, "relay.enc")
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        }
        assertFalse(cfg.toString().contains("secret-token"))
        assertEquals("secret-token", store.load()?.token)
        store.clear()
        assertNull(store.load())
    }
}
