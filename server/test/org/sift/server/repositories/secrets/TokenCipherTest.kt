package org.sift.server.repositories.secrets

import org.sift.server.config.ServerProperties
import org.sift.server.repositories.EncryptedToken
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenCipherTest {
    private val cipher = TokenCipher(properties(randomKey(KEY_BYTES)))

    @Test
    fun `round trips tokens including unicode`() {
        listOf("ghp_abc123", "", "tökén-ü-🔐").forEach { token ->
            val encrypted = cipher.encrypt(token)
            assertEquals(token, cipher.decrypt(encrypted))
            assertEquals(IV_BYTES, encrypted.iv.size)
            assertFalse(encrypted.ciphertext.toString(Charsets.ISO_8859_1).contains("ghp_abc123"))
        }
    }

    @Test
    fun `every encryption uses a fresh IV and produces distinct ciphertext`() {
        val first = cipher.encrypt("same-token")
        val second = cipher.encrypt("same-token")
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertEquals("same-token", cipher.decrypt(second))
    }

    @Test
    fun `tampered ciphertext IV or wrong key are detected`() {
        val encrypted = cipher.encrypt("secret")
        val tamperedCiphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> {
            cipher.decrypt(EncryptedToken(ciphertext = tamperedCiphertext, iv = encrypted.iv))
        }
        val tamperedIv = encrypted.iv.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> {
            cipher.decrypt(EncryptedToken(ciphertext = encrypted.ciphertext, iv = tamperedIv))
        }
        val other = TokenCipher(properties(randomKey(KEY_BYTES)))
        assertFailsWith<AEADBadTagException> { other.decrypt(encrypted) }
        assertEquals("secret", cipher.decrypt(encrypted))
    }

    @Test
    fun `rejects keys that are not 32 bytes or not base64`() {
        listOf(randomKey(16), randomKey(24), randomKey(33), "c2VjcmV0").forEach { key ->
            val failure = assertFailsWith<IllegalStateException>(key) { TokenCipher(properties(key)) }
            assertTrue(failure.message.orEmpty().contains("SIFT_SERVER_ENCRYPTION_KEY"), failure.message)
            assertTrue(failure.message.orEmpty().contains("32 bytes"), failure.message)
        }
        val invalid = assertFailsWith<IllegalStateException> { TokenCipher(properties("not*base64!")) }
        assertTrue(invalid.message.orEmpty().contains("SIFT_SERVER_ENCRYPTION_KEY"), invalid.message)
    }

    private fun properties(key: String) =
        ServerProperties(encryptionKey = key, auth = ServerProperties.Auth(clientId = "sift-web"))

    private fun randomKey(bytes: Int): String =
        ByteArray(bytes).also(SecureRandom()::nextBytes).let(Base64.getEncoder()::encodeToString)
}

private const val KEY_BYTES = 32
private const val IV_BYTES = 12
