package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What pairing leaves behind: where the Mac relay is, which certificate it must show, and our token. */
class RelayConfig(val addr: String, val fp: String, val token: String) {
    // 令牌永远不进日志与诊断包。
    override fun toString() = "RelayConfig(addr=$addr)"
}

/** Seals the pairing before it touches the disk. A seam so the JVM tests can run without a Keystore. */
interface RelayCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws when [blob] is not something this cipher sealed (or its key is gone). */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key (no user authentication, the app uses it
 * in the background). The engine's Node process shares the app's uid and can read every file in
 * `filesDir`, but it cannot call the Keystore API, so what it reads here is ciphertext (P4 H1).
 * Blob layout: 12-byte IV, then ciphertext with the GCM tag.
 */
class KeystoreRelayCipher(private val alias: String = "dsh-relay-token") : RelayCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES + 16) { "short blob" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        }
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val IV_BYTES = 12
    }
}

/**
 * The relay pairing in the app's private files, sealed by [cipher] (`relay.enc`, 0600, replaced
 * atomically). The plaintext lives in this process's memory only; the token is never returned to
 * Dart. A plaintext `relay.json` from an earlier build is sealed and deleted on first read.
 */
class RelayTokenStore(private val dir: File, private val cipher: RelayCipher = KeystoreRelayCipher()) {
    private val sealed = File(dir, "relay.enc")
    private val legacy = File(dir, "relay.json")
    @Volatile private var cached: RelayConfig? = null
    @Volatile private var unreadable = false

    @Synchronized
    fun load(): RelayConfig? {
        cached?.let { return it }
        val fromSealed = if (sealed.isFile) read(sealed.readBytes().let { runCatching { cipher.decrypt(it) }.getOrNull() }) else null
        val config = fromSealed ?: legacy.takeIf { it.isFile }?.let { read(it.readBytes()) }?.also { save(it) }
        if (legacy.isFile && config != null) wipe(legacy)
        cached = config
        unreadable = config == null && sealed.isFile
        return config
    }

    /** `relay.enc` is there but cannot be opened: theUI asks for a new pairing instead of showing "not paired". */
    fun unreadable(): Boolean {
        load()
        return unreadable
    }

    /** Seals and opens a probe blob: pairing checks this before it spends the Mac's one-time code. */
    fun check() {
        val probe = ByteArray(16).also { SecureRandom().nextBytes(it) }
        check(cipher.decrypt(cipher.encrypt(probe)).contentEquals(probe)) { "sealed probe did not round-trip" }
    }

    private fun read(plain: ByteArray?): RelayConfig? {
        if (plain == null) return null
        return try {
            val m = Json.parse(String(plain, Charsets.UTF_8)) as? Map<*, *> ?: return null
            val addr = m["addr"] as? String
            val fp = m["fp"] as? String
            val token = m["token"] as? String
            if (addr.isNullOrEmpty() || fp.isNullOrEmpty() || token.isNullOrEmpty()) null else RelayConfig(addr, fp, token)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun save(config: RelayConfig) {
        dir.mkdirs()
        val blob = cipher.encrypt(Json.write(linkedMapOf("addr" to config.addr, "fp" to config.fp, "token" to config.token)).toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "relay.enc.tmp")
        tmp.delete()
        tmp.createNewFile()
        tmp.setReadable(false, false)
        tmp.setWritable(false, false)
        tmp.setExecutable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        tmp.writeBytes(blob)
        Files.move(tmp.toPath(), sealed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        cached = config
        unreadable = false
    }

    @Synchronized
    fun clear() {
        cached = null
        unreadable = false
        sealed.delete()
        wipe(legacy)
        File(dir, "relay.enc.tmp").delete()
    }

    /** Overwrites before deleting: the old plaintext should not linger in a freed block. */
    private fun wipe(f: File) {
        if (!f.isFile) return
        runCatching { f.writeBytes(ByteArray(f.length().toInt()).also { SecureRandom().nextBytes(it) }) }
        f.delete()
    }
}
