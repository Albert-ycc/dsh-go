package io.github.ccch1mneyyy.dsh_app.phone.relay

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * First pairing with the Mac relay (§5.5). The one-time code never goes on the wire: both sides
 * derive `key = PBKDF2-HMAC-SHA256(code, salt = nonce, 200000, 32)` and exchange HMACs bound to
 * the certificate fingerprint the phone saw (`seenFp`) and the one the relay really has (`fp`).
 * A man in the middle swapping the certificate cannot produce a proof that matches both.
 *
 * Wire format (fixed with the relay side): nonce, fingerprints and proofs are lowercase hex;
 * fingerprint = SHA-256 of the leaf certificate's DER; messages are `"pair|" + seenFp` and
 * `"srv|" + fp` in UTF-8; the token is base64url.
 */
object RelayPairing {
    const val ITERATIONS = 200_000

    /** Crockford base32 as the Mac prints it: upper case, no separators; typing slips (o, i, l) read as 0, 1, 1. */
    fun normalizeCode(raw: String): String =
        raw.uppercase().filterNot { it == ' ' || it == '-' || it == '\t' }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

    fun deriveKey(code: String, nonce: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), nonce, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hmacHex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun clientProof(key: ByteArray, seenFp: String): String = hmacHex(key, "pair|$seenFp")

    fun serverProof(key: ByteArray, fp: String): String = hmacHex(key, "srv|$fp")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    sealed interface Outcome {
        class Paired(val config: RelayConfig) : Outcome
        class Failed(val error: RelayError) : Outcome
    }

    /**
     * Pairs with the relay at [addr] (`ip:port`) using the code the Mac printed, and saves the
     * result in [store] only when the relay's own proof checks out. Blocking (PBKDF2 takes ~1 s).
     */
    fun pair(
        addr: String, rawCode: String, name: String, store: RelayTokenStore, client: RelayClient,
        connectMs: Int = 3_000, nonceSource: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
    ): Outcome {
        val code = normalizeCode(rawCode)
        // 先确认存得下去：中继签发令牌后手机存不下，Mac 上就留一个无主令牌，配对码也已作废。
        try {
            store.check()
        } catch (e: Exception) {
            return Outcome.Failed(RelayError(RelayCodes.STORE_FAILED, e.message ?: e.javaClass.simpleName))
        }
        val seenFp = try {
            RelayHttp.probeFingerprint(addr, connectMs)
        } catch (e: IOException) {
            return Outcome.Failed(RelayError(RelayCodes.UNREACHABLE, e.message ?: e.javaClass.simpleName, retryable = true))
        }
        val nonce = nonceSource()
        val key = deriveKey(code, nonce)
        val body = linkedMapOf<String, Any?>(
            "nonce" to hex(nonce), "seenFp" to seenFp, "proof" to clientProof(key, seenFp), "name" to name.take(60),
        )
        // 这条连接钉在刚看到的指纹上：之后换证书的中间人到不了令牌这一步。
        val r = client.send(addr, seenFp, null, "POST", "/v1/pair", body, 10_000) { m ->
            (m["token"] as? String).orEmpty() to (m["serverProof"] as? String).orEmpty()
        }
        return when (r) {
            is RelayResult.Err -> Outcome.Failed(r.error)
            is RelayResult.Ok -> {
                val (token, proof) = r.value
                val expected = serverProof(key, seenFp)
                if (token.isEmpty() || !MessageDigest.isEqual(expected.toByteArray(), proof.toByteArray())) {
                    Outcome.Failed(RelayError(RelayCodes.PIN_MISMATCH, "relay proof does not match"))
                } else {
                    val config = RelayConfig(addr, seenFp, token)
                    try {
                        store.save(config)
                        Outcome.Paired(config)
                    } catch (e: Exception) {
                        Outcome.Failed(RelayError(RelayCodes.STORE_FAILED, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }
    }
}
