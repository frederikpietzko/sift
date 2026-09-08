package org.sift.server.users

import org.sift.server.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each test runs inside a Spring transaction that is rolled back, so the shared database stays clean. */
@Transactional
class UserRepositoryTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var users: UserRepository

    @Test
    fun `first upsert inserts the row as given`() {
        val candidate = user(subject = "sub-1", username = "alice", email = "alice@example.org")

        val stored = users.upsert(candidate)

        assertEquals(candidate.id, stored.id)
        assertEquals(ISSUER, stored.issuer)
        assertEquals("sub-1", stored.subject)
        assertEquals("alice", stored.username)
        assertEquals("alice@example.org", stored.email)
        assertTrue(candidate.createdAt.isEqual(stored.createdAt))
        assertTrue(candidate.lastSeenAt.isEqual(stored.lastSeenAt))
        assertEquals(stored, users.findById(stored.id))
        assertEquals(stored, users.findByIdentity(ISSUER, "sub-1"))
    }

    @Test
    fun `second upsert for the same identity keeps id and created_at but refreshes display data and last_seen_at`() {
        val first = users.upsert(user(subject = "sub-2", username = "alice", email = "alice@example.org"))
        val later = first.lastSeenAt.plusHours(1)

        val second = users.upsert(
            user(subject = "sub-2", username = "alice.renamed", email = null, createdAt = later, lastSeenAt = later),
        )

        assertEquals(first.id, second.id)
        assertTrue(first.createdAt.isEqual(second.createdAt))
        assertEquals("alice.renamed", second.username)
        assertNull(second.email)
        assertTrue(later.isEqual(second.lastSeenAt))
        assertEquals(second, users.findById(first.id))
    }

    @Test
    fun `the same subject at a different issuer is a different user`() {
        val keycloak = users.upsert(user(subject = "shared-sub", username = "alice", email = null))
        val entra = users.upsert(user(issuer = "https://login.example.org/tenant", subject = "shared-sub", username = "bob", email = null))

        assertNotEquals(keycloak.id, entra.id)
        assertEquals("alice", assertNotNull(users.findById(keycloak.id)).username)
        assertEquals("bob", assertNotNull(users.findById(entra.id)).username)
        assertNull(users.findById(UUID.randomUUID()))
        assertNull(users.findByIdentity("https://nowhere.example.org", "shared-sub"))
    }

    private fun user(
        issuer: String = ISSUER,
        subject: String,
        username: String,
        email: String?,
        createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
        lastSeenAt: OffsetDateTime = createdAt,
    ): User = User(
        id = UUID.randomUUID(),
        issuer = issuer,
        subject = subject,
        username = username,
        email = email,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
    )

    companion object {
        private const val ISSUER = "https://issuer.test/realms/sift"
    }
}
