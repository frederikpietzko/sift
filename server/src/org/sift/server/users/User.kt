package org.sift.server.users

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A person known to the server. Identity is the `(issuer, subject)` pair of the OAuth2 provider; the local [id]
 * is what other tables reference so display data and provider migrations never touch foreign keys.
 */
data class User(
    val id: UUID,
    val issuer: String,
    val subject: String,
    val username: String,
    val email: String?,
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
)
