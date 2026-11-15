package io.github.ccch1mneyyy.dsh_app.phone.relay

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** AES-GCM with an in-memory key: the same blob shape as [KeystoreRelayCipher], without a Keystore. */
class SoftCipher(key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : RelayCipher {
    private val k = SecretKeySpec(key, "AES")
    var fail = false
    var failEncrypt = false

    override fun encrypt(plain: ByteArray): ByteArray {
        check(!failEncrypt) { "keystore unavailable" }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        check(!fail) { "key gone" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

class RelayTokenStoreTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("relay-store").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun filesText() = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Test fun whatTheEngineCanReadOnDiskIsCiphertext() {
        val cipher = SoftCipher()
        RelayTokenStore(dir, cipher).save(RelayConfig("10.0.0.2:8731", "ff".repeat(32), "secret-token-value"))
        val disk = filesText()
        assertFalse("the token must not be on disk in clear", disk.contains("secret-token-value"))
        assertFalse(disk.contains("10.0.0.2"))
        // 另一个进程（新实例，同一密钥）能读回；没有密钥的读不出。
        assertEquals("secret-token-value", RelayTokenStore(dir, cipher).load()?.token)
        assertNull(RelayTokenStore(dir, SoftCipher()).load())
    }

    @Test fun sealedFileIsPrivate() {
        RelayTokenStore(dir, SoftCipher()).save(RelayConfig("a:1", "b", "c"))
        val f = File(dir, "relay.enc")
        assertTrue(f.isFile)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
    }

    @Test fun anOldPlaintextFileIsSealedAndDeletedOnFirstRead() {
        File(dir, "relay.json").writeText("""{"addr":"10.0.0.2:8731","fp":"abcd","token":"legacy-token"}""")
        val cipher = SoftCipher()
        val store = RelayTokenStore(dir, cipher)
        assertEquals("legacy-token", store.load()?.token)
        assertFalse("plaintext is gone", File(dir, "relay.json").exists())
        assertTrue(File(dir, "relay.enc").isFile)
        assertFalse(filesText().contains("legacy-token"))
        assertEquals("legacy-token", RelayTokenStore(dir, cipher).load()?.token)
    }

    @Test fun aKeyThatCannotDecryptReadsAsNotPairedRatherThanCrashing() {
        val cipher = SoftCipher()
        RelayTokenStore(dir, cipher).save(RelayConfig("a:1", "b", "c"))
        cipher.fail = true
        val store = RelayTokenStore(dir, cipher)
        assertNull(store.load())
    }

    @Test fun aFileThatCannotBeOpenedIsUnreadableNotJustUnpaired() {
        val cipher = SoftCipher()
        RelayTokenStore(dir, cipher).save(RelayConfig("a:1", "b", "c"))
        assertFalse(RelayTokenStore(dir, cipher).unreadable())
        cipher.fail = true
        val store = RelayTokenStore(dir, cipher)
        assertNull(store.load())
        assertTrue(store.unreadable())
        assertFalse("never paired is not unreadable", RelayTokenStore(File(dir, "other"), cipher).unreadable())
        cipher.fail = false
        store.save(RelayConfig("a:1", "b", "c"))
        assertFalse(store.unreadable())
    }

    @Test fun checkFailsWhenTheKeystoreCannotSeal() {
        val cipher = SoftCipher()
        RelayTokenStore(dir, cipher).check()
        cipher.failEncrypt = true
        assertThrows(IllegalStateException::class.java) { RelayTokenStore(dir, cipher).check() }
    }

    @Test fun clearRemovesBothFiles() {
        File(dir, "relay.json").writeText("""{"addr":"a:1","fp":"b","token":"c"}""")
        val store = RelayTokenStore(dir, SoftCipher())
        store.load()
        store.clear()
        assertNull(store.load())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun readsAreServedFromMemoryAfterTheFirst() {
        val cipher = SoftCipher()
        val store = RelayTokenStore(dir, cipher)
        store.save(RelayConfig("a:1", "b", "c"))
        cipher.fail = true
        assertEquals("c", store.load()?.token)
    }
}
