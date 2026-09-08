package org.sift.server.users

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.sift.server.config.ServerProperties
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserServiceTest {
    private val users = mockk<UserRepository>()
    private val now: Instant = Instant.parse("2026-09-07T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val upserted = slot<User>()

    @Test
    fun `maps issuer subject and the default claims and stamps both timestamps with the clock`() {
        val service = service(ServerProperties.Auth.Claims())
        every { users.upsert(capture(upserted)) } answers { firstArg<User>().copy(id = STORED_ID) }

        val user = service.provision(jwt(mapOf("preferred_username" to "alice", "email" to "alice@example.org")))

        assertEquals(STORED_ID, user.id)
        val candidate = upserted.captured
        assertEquals(ISSUER, candidate.issuer)
        assertEquals("sub-1", candidate.subject)
        assertEquals("alice", candidate.username)
        assertEquals("alice@example.org", candidate.email)
        assertTrue(OffsetDateTime.ofInstant(now, ZoneOffset.UTC).isEqual(candidate.createdAt))
        assertTrue(OffsetDateTime.ofInstant(now, ZoneOffset.UTC).isEqual(candidate.lastSeenAt))
    }

    @Test
    fun `honours configured claim names`() {
        val service = service(ServerProperties.Auth.Claims(username = "upn", email = "mail"))
        every { users.upsert(capture(upserted)) } answers { firstArg() }

        service.provision(
            jwt(
                mapOf(
                    "upn" to "alice@corp.example.org",
                    "mail" to "alice.mail@corp.example.org",
                    "preferred_username" to "ignored",
                    "email" to "ignored@example.org",
                ),
            ),
        )

        assertEquals("alice@corp.example.org", upserted.captured.username)
        assertEquals("alice.mail@corp.example.org", upserted.captured.email)
    }

    @Test
    fun `falls back to the subject when the username claim is missing or blank and leaves email null`() {
        val service = service(ServerProperties.Auth.Claims())
        every { users.upsert(capture(upserted)) } answers { firstArg() }

        service.provision(jwt(emptyMap()))
        assertEquals("sub-1", upserted.captured.username)
        assertNull(upserted.captured.email)

        service.provision(jwt(mapOf("preferred_username" to "  ", "email" to "")))
        assertEquals("sub-1", upserted.captured.username)
        assertNull(upserted.captured.email)
    }

    @Test
    fun `rejects tokens without issuer or subject`() {
        val service = service(ServerProperties.Auth.Claims())

        assertFailsWith<IllegalArgumentException> {
            service.provision(Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").build())
        }
        assertFailsWith<IllegalArgumentException> {
            service.provision(Jwt.withTokenValue("t").header("alg", "none").issuer(ISSUER).build())
        }
    }

    private fun service(claims: ServerProperties.Auth.Claims): UserService = UserService(
        users = users,
        properties = ServerProperties(
            encryptionKey = "dGVzdA==",
            auth = ServerProperties.Auth(clientId = "sift-web", claims = claims),
        ),
        clock = clock,
    )

    private fun jwt(claims: Map<String, Any>): Jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .issuer(ISSUER)
        .subject("sub-1")
        .apply { claims.forEach { entry -> claim(entry.key, entry.value) } }
        .build()

    companion object {
        private const val ISSUER = "https://issuer.test/realms/sift"
        private val STORED_ID: UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f90-a1b2c3d4e5f6")
    }
}
