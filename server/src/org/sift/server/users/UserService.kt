package org.sift.server.users

import org.sift.server.api.NotFoundException
import org.sift.server.config.ServerProperties
import org.sift.server.users.persistence.UserRepository
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class UserService(
    private val users: UserRepository,
    private val properties: ServerProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Makes the caller of an authenticated request known: the `(iss, sub)` of [jwt] is inserted on first sight and
     * refreshed (username, email, `last_seen_at`) on every later request. The username comes from the configured
     * claim (`sift.server.auth.claims.username`) and falls back to the subject when the provider omits it.
     */
    @Transactional
    fun provision(jwt: Jwt): User {
        val claims = properties.auth.claims
        val issuer = requireNotNull(jwt.issuer) { "JWT has no issuer claim" }.toString()
        val subject = requireNotNull(jwt.subject) { "JWT has no subject claim" }
        val now = OffsetDateTime.now(clock)
        return users.upsert(
            User(
                id = UUID.randomUUID(),
                issuer = issuer,
                subject = subject,
                username = jwt.stringClaim(claims.username) ?: subject,
                email = jwt.stringClaim(claims.email),
                createdAt = now,
                lastSeenAt = now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): User = users.findById(id) ?: throw NotFoundException("User $id not found")

    private fun Jwt.stringClaim(name: String): String? = getClaimAsString(name)?.takeUnless(String::isBlank)
}
