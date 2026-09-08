package org.sift.server.repositories

import java.time.OffsetDateTime
import java.util.UUID

data class Repository(
    val id: UUID,
    val name: String,
    val url: String,
    val token: EncryptedToken?,
    val secretName: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val hasToken: Boolean get() = token != null
}

/** Access token at rest: AES-GCM ciphertext plus the IV it was encrypted with (see `TokenCipher`). */
class EncryptedToken(val ciphertext: ByteArray, val iv: ByteArray)
