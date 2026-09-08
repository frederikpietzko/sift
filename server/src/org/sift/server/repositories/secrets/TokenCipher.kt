package org.sift.server.repositories.secrets

import org.sift.server.config.ServerProperties
import org.sift.server.repositories.EncryptedToken
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM encryption of repository access tokens at rest; the key comes from `SIFT_SERVER_ENCRYPTION_KEY`. */
@Component
class TokenCipher(properties: ServerProperties) {
    private val key = SecretKeySpec(decodeKey(properties.encryptionKey), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): EncryptedToken {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return EncryptedToken(ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), iv = iv)
    }

    fun decrypt(token: EncryptedToken): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, token.iv))
        return cipher.doFinal(token.ciphertext).toString(Charsets.UTF_8)
    }

    private fun decodeKey(encoded: String): ByteArray {
        val decoded = runCatching { Base64.getDecoder().decode(encoded.trim()) }.getOrElse { exception ->
            throw IllegalStateException("SIFT_SERVER_ENCRYPTION_KEY must be valid base64", exception)
        }
        check(decoded.size == KEY_BYTES) {
            "SIFT_SERVER_ENCRYPTION_KEY must decode to exactly $KEY_BYTES bytes (AES-256), got ${decoded.size}"
        }
        return decoded
    }
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_BYTES = 32
private const val IV_BYTES = 12
private const val TAG_BITS = 128
