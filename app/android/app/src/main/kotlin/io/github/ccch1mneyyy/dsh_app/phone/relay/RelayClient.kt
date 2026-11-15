package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.PhoneTasks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// The phone side of the Mac relay (docs/design/zhuoyitong/P1-架构-手机操控中转.md §4): HTTPS to a
// self-signed certificate pinned by its SHA-256, bearer token, one request id per write so a
// resend after a lost response cannot act twice. Blocking calls on the phone-control worker thread.

/** Relay error: either one the relay answered (§4.6 `code`) or one found on this side (see [RelayCodes]). */
class RelayError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val hint: String? = null,
    val http: Int = 0,
)

object RelayCodes {
    // 本端自己判定的，不是中继返回的码。
    const val UNREACHABLE = "relay_unreachable"
    const val PIN_MISMATCH = "pin_mismatch"
    const val UNCONFIGURED = "unconfigured"
    const val BAD_RESPONSE = "bad_response"

    /** 配对结果存不进 Keystore 加密文件（Keystore 不可用或写盘失败）；中继那边没有签发令牌时返回。 */
    const val STORE_FAILED = "store_failed"

    /** 手机上有配对文件，但解不开（密钥丢了或文件坏了）：需要重新配对。 */
    const val STORE_UNREADABLE = "store_unreadable"

    // 中继返回的码（§4.6）。
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN_SOURCE = "forbidden_source"
    const val CANCELLED = "cancelled"
    const val DEVICE_LOCKED = "device_locked"
    const val BAD_ARGS = "bad_args"
    const val HALTED = "halted"
    const val COMMAND_FAILED = "command_failed"
    const val BUSY = "busy"
    const val DEVICE_OFFLINE = "device_offline"
    const val DEVICE_UNAUTHORIZED = "device_unauthorized"
    const val DEVICE_TIMEOUT = "device_timeout"
    const val INTERNAL = "internal"

    /** The code a status without a readable body stands for. */
    fun ofHttp(status: Int): String = when (status) {
        401 -> UNAUTHORIZED
        403 -> FORBIDDEN_SOURCE
        409 -> CANCELLED
        422 -> BAD_ARGS
        423 -> HALTED
        424 -> COMMAND_FAILED
        429 -> BUSY
        503 -> DEVICE_OFFLINE
        504 -> DEVICE_TIMEOUT
        else -> INTERNAL
    }
}

sealed interface RelayResult<out T> {
    class Ok<T>(val value: T) : RelayResult<T>
    class Err(val error: RelayError) : RelayResult<Nothing>
}

data class RelayDevice(
    val state: String?,
    val via: String?,
    val target: String?,
    val model: String?,
    val since: Long?,
    val locked: Boolean?,
    val screenOn: Boolean?,
)

data class RelayDeviceInfo(val halted: Boolean, val device: RelayDevice?, val queueDepth: Int, val version: String?)

data class RelayApp(val bundle: String, val label: String?, val alias: String?)

/** What the phone-control code needs from the relay; [RelayClient] over HTTPS, a fake in driver tests. */
interface RelayApi {
    fun configured(): Boolean

    /** A pairing file is there but cannot be opened ([RelayCodes.STORE_UNREADABLE]). */
    fun storeUnreadable(): Boolean = false

    /** `/v1/device`; an answer younger than [maxAgeMs] is reused (errors only for 1 s). 0 always asks. */
    fun device(maxAgeMs: Long = RelayClient.DEVICE_CACHE_MS): RelayResult<RelayDeviceInfo>

    /** Raw `/v1/observe` JSON (§4.3); [RelayTreeMapper] reads it. */
    fun observe(image: Boolean): RelayResult<Map<String, Any?>>

    /** `/v1/act` with `{op, ...}` (§4.4). */
    fun act(body: Map<String, Any?>): RelayResult<Map<String, Any?>>

    fun launch(bundle: String, ability: String? = null, module: String? = null): RelayResult<Map<String, Any?>>

    fun apps(query: String? = null): RelayResult<List<RelayApp>>

    /** The phone's wireless-debugging address, for the Mac to connect to. */
    fun target(addr: String): RelayResult<Map<String, Any?>>

    /** Cancels the in-flight and queued commands of [task] on the Mac. */
    fun stop(task: Long): RelayResult<Int>

    fun halt(): RelayResult<Unit>
}

data class RelayTimeouts(
    val connectMs: Int = 3_000,
    val observeMs: Int = 18_000,
    val actMs: Int = 8_000,
    val launchMs: Int = 11_000,
    val otherMs: Int = 5_000,
)

class RelayClient(
    private val store: RelayTokenStore,
    private val taskId: () -> Long = { 0L },
    private val timeouts: RelayTimeouts = RelayTimeouts(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
) : RelayApi {
    @Volatile private var deviceCache: Pair<Long, RelayResult<RelayDeviceInfo>>? = null

    /** 最近一次 observe 回的 `device.locked` 和读到它的时间；`/v1/device` 里的 locked 是中继的缓存，不用它判锁屏。 */
    @Volatile private var observedLock: Pair<Long, Boolean?>? = null

    override fun configured(): Boolean = store.load() != null

    override fun storeUnreadable(): Boolean = store.unreadable()

    override fun device(maxAgeMs: Long): RelayResult<RelayDeviceInfo> {
        val now = clock()
        deviceCache?.let { (at, r) ->
            if (now - at < (if (r is RelayResult.Ok) maxAgeMs else minOf(maxAgeMs, ERROR_CACHE_MS))) return r
        }
        val r = request("GET", "/v1/device", null, timeouts.otherMs) { parseDevice(it) }
        deviceCache = clock() to r
        return r
    }

    /** The `device.locked` of the last `/v1/observe` answer, without a network call; null when unknown or older than 15 s. */
    fun cachedLocked(): Boolean? {
        val (at, locked) = observedLock ?: return null
        return if (clock() - at > LOCK_FRESH_MS) null else locked
    }

    override fun observe(image: Boolean): RelayResult<Map<String, Any?>> =
        request("POST", "/v1/observe", mapOf("image" to image), timeouts.observeMs) { m ->
            observedLock = clock() to ((m["device"] as? Map<*, *>)?.get("locked") as? Boolean)
            m
        }

    override fun act(body: Map<String, Any?>): RelayResult<Map<String, Any?>> =
        request("POST", "/v1/act", body, timeouts.actMs) { it }

    override fun launch(bundle: String, ability: String?, module: String?): RelayResult<Map<String, Any?>> {
        val body = linkedMapOf<String, Any?>("bundle" to bundle)
        ability?.let { body["ability"] = it }
        module?.let { body["module"] = it }
        return request("POST", "/v1/launch", body, timeouts.launchMs) { it }
    }

    override fun apps(query: String?): RelayResult<List<RelayApp>> {
        val path = if (query.isNullOrEmpty()) "/v1/apps" else "/v1/apps?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        return request("GET", path, null, timeouts.otherMs) { m ->
            (m["apps"] as? List<*>).orEmpty().mapNotNull { a ->
                val e = a as? Map<*, *> ?: return@mapNotNull null
                val bundle = e["bundle"] as? String ?: return@mapNotNull null
                RelayApp(bundle, e["label"] as? String, e["alias"] as? String)
            }
        }
    }

    override fun target(addr: String): RelayResult<Map<String, Any?>> {
        deviceCache = null
        return request("POST", "/v1/target", mapOf("addr" to addr), timeouts.otherMs) { it }
    }

    override fun stop(task: Long): RelayResult<Int> =
        request("POST", "/v1/stop", mapOf("task" to task.toString()), timeouts.otherMs) { (it["cancelled"] as? Number)?.toInt() ?: 0 }

    override fun halt(): RelayResult<Unit> {
        deviceCache = null
        return request("POST", "/v1/halt", emptyMap<String, Any?>(), timeouts.otherMs) { }
    }

    private fun <T> request(method: String, path: String, body: Map<String, Any?>?, readMs: Int, parse: (Map<String, Any?>) -> T): RelayResult<T> {
        val cfg = store.load() ?: return RelayResult.Err(RelayError(RelayCodes.UNCONFIGURED, "no relay paired"))
        return send(cfg.addr, cfg.fp, cfg.token, method, path, body, readMs, parse)
    }

    /**
     * One logical request. A network failure before any answer is retried once with the same
     * `X-Request-Id` (the relay replays a finished write instead of redoing it); `202 in_progress`
     * means the first copy is still running, so the same id is asked again until the read budget ends.
     */
    internal fun <T> send(
        addr: String, fp: String, token: String?, method: String, path: String, body: Map<String, Any?>?, readMs: Int,
        parse: (Map<String, Any?>) -> T,
    ): RelayResult<T> {
        val id = newId()
        val headers = linkedMapOf("X-Dsh-Relay" to "1", "X-Request-Id" to id, "X-Dsh-Task" to taskId().toString(), "Accept" to "application/json")
        if (token != null) headers["Authorization"] = "Bearer $token"
        val text = body?.let { Json.write(it) }
        val deadline = clock() + readMs
        var failures = 0
        while (true) {
            val reply = try {
                RelayHttp.exchange(addr, fp, method, path, headers, text, timeouts.connectMs, readMs)
            } catch (e: IOException) {
                if (RelayHttp.isPinMismatch(e)) return RelayResult.Err(RelayError(RelayCodes.PIN_MISMATCH, "certificate fingerprint changed"))
                if (++failures < 2) continue
                return RelayResult.Err(RelayError(RelayCodes.UNREACHABLE, e.message ?: e.javaClass.simpleName, retryable = true))
            }
            if (reply.status == 202) {
                if (clock() >= deadline) return RelayResult.Err(RelayError(RelayCodes.DEVICE_TIMEOUT, "still in progress", http = 202))
                pause(RETRY_IN_PROGRESS_MS)
                continue
            }
            return interpret(reply, parse)
        }
    }

    private fun <T> interpret(reply: HttpReply, parse: (Map<String, Any?>) -> T): RelayResult<T> {
        val map = try {
            Json.parse(reply.body) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        @Suppress("UNCHECKED_CAST")
        val m = map as? Map<String, Any?>
        val err = m?.get("error") as? Map<*, *>
        if (reply.status in 200..299 && m != null && m["ok"] != false && err == null) {
            return try {
                RelayResult.Ok(parse(m))
            } catch (e: Exception) {
                RelayResult.Err(RelayError(RelayCodes.BAD_RESPONSE, e.message ?: e.javaClass.simpleName, http = reply.status))
            }
        }
        if (reply.status in 200..299 && err == null) {
            return RelayResult.Err(RelayError(RelayCodes.BAD_RESPONSE, "unreadable body", http = reply.status))
        }
        val code = (err?.get("code") as? String)?.takeIf { it.isNotEmpty() } ?: RelayCodes.ofHttp(reply.status)
        return RelayResult.Err(
            RelayError(
                code, (err?.get("message") as? String).orEmpty(), err?.get("retryable") == true,
                err?.get("hint") as? String, reply.status,
            ),
        )
    }

    companion object {
        const val DEVICE_CACHE_MS = 5_000L
        private const val ERROR_CACHE_MS = 1_000L
        private const val LOCK_FRESH_MS = 15_000L
        private const val RETRY_IN_PROGRESS_MS = 300L

        internal fun parseDevice(m: Map<String, Any?>): RelayDeviceInfo {
            val d = m["device"] as? Map<*, *>
            val device = d?.let {
                RelayDevice(
                    it["state"] as? String, it["via"] as? String, it["target"] as? String, it["model"] as? String,
                    (it["since"] as? Number)?.toLong(), it["locked"] as? Boolean, it["screenOn"] as? Boolean,
                )
            }
            val depth = ((m["queue"] as? Map<*, *>)?.get("depth") as? Number)?.toInt() ?: 0
            return RelayDeviceInfo(m["halted"] == true, device, depth, m["version"] as? String)
        }
    }
}

internal class HttpReply(val status: Int, val body: String)

/** The certificate on the wire is not the one pinned. */
internal class PinMismatchException : CertificateException("relay certificate does not match the pinned fingerprint")

internal object RelayHttp {
    private const val MAX_BODY = 8 * 1024 * 1024
    private val ADDR = Regex("^[0-9A-Za-z.-]+:\\d{1,5}$")
    private val contexts = ConcurrentHashMap<String, SSLContext>()

    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun isPinMismatch(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is PinMismatchException }

    private fun pinned(fp: String): SSLContext = contexts.getOrPut(fp) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not used")
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw PinMismatchException()
                if (!MessageDigest.isEqual(fingerprint(leaf.encoded).toByteArray(), fp.toByteArray())) throw PinMismatchException()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
    }

    fun exchange(
        addr: String, fp: String, method: String, path: String, headers: Map<String, String>, body: String?,
        connectMs: Int, readMs: Int,
    ): HttpReply {
        if (!ADDR.matches(addr)) throw IOException("bad relay address")
        val conn = URL("https://$addr$path").openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = pinned(fp).socketFactory
            // 信任的是指纹，不是主机名：中继的地址是 IP，证书是自签的。
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return HttpReply(status, stream?.use { read(it) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BODY) throw IOException("relay answer too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** The fingerprint of whatever certificate [addr] shows. Only pairing uses it, and the pairing proof binds the result. */
    fun probeFingerprint(addr: String, connectMs: Int): String {
        if (!ADDR.matches(addr)) throw IOException("bad relay address")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toInt()
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = connectMs
            it.connect(InetSocketAddress(host, port), connectMs)
            it.startHandshake()
            val leaf = it.session.peerCertificates.firstOrNull() ?: throw IOException("no certificate")
            return fingerprint(leaf.encoded)
        }
    }
}

/** The sentence the model gets for a relay failure (§4.6), and for the person in [PhoneControl]'s own strings. */
object RelayMessages {
    // l10n:keep agent tool result
    fun forModel(e: RelayError, addr: String? = null): String = when (e.code) {
        RelayCodes.UNAUTHORIZED, RelayCodes.PIN_MISMATCH ->
            "Mac 中继不认这台手机的配对（令牌无效，或中继的证书变了）。请用户到 DSH 设置 → 手机操控 → Mac 中继 重新配对。"
        RelayCodes.FORBIDDEN_SOURCE ->
            "Mac 中继拒绝了这台手机的网络地址。请用户检查手机和 Mac 是否在同一个局域网（或 Tailscale）里，再到 设置 → 手机操控 → Mac 中继 重新配对。"
        RelayCodes.CANCELLED -> PhoneTasks.STOPPED_MESSAGE
        RelayCodes.DEVICE_LOCKED -> "手机已锁屏。请用户解锁后再继续。"
        RelayCodes.BAD_ARGS -> "内部错误：给 Mac 中继的参数不对（${e.message}）。"
        RelayCodes.HALTED -> "Mac 上的中继被暂停了。请用户在 Mac 上执行 dsh-relay resume。"
        RelayCodes.COMMAND_FAILED -> "手机没有接受这个操作：${e.message.lineSequence().firstOrNull().orEmpty()}。"
        RelayCodes.BUSY -> "前面的操作还没结束，稍后再试。"
        RelayCodes.DEVICE_OFFLINE ->
            "Mac 连不上这台手机（无线调试断了或端口变了）。请用户到 设置 → 手机操控 → Mac 中继 更新无线调试地址，或者插上 USB。"
        RelayCodes.DEVICE_UNAUTHORIZED -> "手机还没有授权这台 Mac。请用户在手机上点「允许」。"
        RelayCodes.DEVICE_TIMEOUT -> "手机上的这一步超时了，可能已经执行，也可能没有。先用 screen 看一下再决定。"
        RelayCodes.INTERNAL -> "Mac 中继内部出错：${e.message}。"
        RelayCodes.UNREACHABLE ->
            "连不上 Mac 中继${addr?.let { "（$it）" } ?: ""}。请用户确认 Mac 开着、中继在运行、手机和 Mac 在同一个网络里。"
        RelayCodes.STORE_UNREADABLE -> "手机上保存的 Mac 中继配对读不出来了。请用户到 DSH 设置 → 手机操控 → Mac 中继 重新配对。"
        RelayCodes.STORE_FAILED -> "手机没能保存 Mac 中继的配对。请用户稍后重试。"
        RelayCodes.UNCONFIGURED -> "还没有配置 Mac 中继。请用户到 DSH 设置 → 手机操控 → Mac 中继 完成配对。"
        else -> "Mac 中继返回了看不懂的内容（${e.code}）。"
    }
}
