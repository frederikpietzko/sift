package org.sift.server.repositories

import org.sift.server.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each test runs inside a Spring transaction that is rolled back, so the shared database stays clean. */
@Transactional
class RepositoryRepositoryTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var repositories: RepositoryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `insert and find round trip all columns including the encrypted token`() {
        val token = EncryptedToken(ciphertext = byteArrayOf(1, 2, 3, 4), iv = ByteArray(12) { it.toByte() })
        val inserted = repositories.insert(repository(name = "alpha", token = token))

        val found = assertNotNull(repositories.findById(inserted.id))
        assertEquals(inserted.id, found.id)
        assertEquals("alpha", found.name)
        assertEquals(inserted.url, found.url)
        assertContentEquals(token.ciphertext, assertNotNull(found.token).ciphertext)
        assertContentEquals(token.iv, assertNotNull(found.token).iv)
        assertEquals("sift-repo-${inserted.id}", found.secretName)
        assertTrue(inserted.createdAt.isEqual(found.createdAt))
        assertTrue(inserted.updatedAt.isEqual(found.updatedAt))
        assertEquals(found.id, assertNotNull(repositories.findByName("alpha")).id)
        assertNull(repositories.findByName("missing"))
        assertNull(repositories.findById(UUID.randomUUID()))
    }

    @Test
    fun `findAll is sorted by name and tokenless rows map to null token`() {
        repositories.insert(repository(name = "zulu", token = null))
        repositories.insert(repository(name = "bravo", token = null))
        repositories.insert(repository(name = "mike", token = EncryptedToken(byteArrayOf(9), byteArrayOf(8))))

        val all = repositories.findAll()
        assertEquals(listOf("bravo", "mike", "zulu"), all.map { it.name })
        assertFalse(all.single { it.name == "bravo" }.hasToken)
        assertNull(all.single { it.name == "bravo" }.secretName)
        assertTrue(all.single { it.name == "mike" }.hasToken)
    }

    @Test
    fun `update rewrites mutable columns and delete removes the row`() {
        val inserted = repositories.insert(repository(name = "charlie", token = null))
        val updated = Repository(
            id = inserted.id,
            name = inserted.name,
            url = "https://example.org/moved",
            token = EncryptedToken(byteArrayOf(7, 7), byteArrayOf(6, 6)),
            secretName = "sift-repo-${inserted.id}",
            createdAt = inserted.createdAt,
            updatedAt = inserted.updatedAt.plusMinutes(5),
        )
        repositories.update(updated)

        val found = assertNotNull(repositories.findById(inserted.id))
        assertEquals("https://example.org/moved", found.url)
        assertContentEquals(byteArrayOf(7, 7), assertNotNull(found.token).ciphertext)
        assertEquals("sift-repo-${inserted.id}", found.secretName)
        assertTrue(updated.updatedAt.isEqual(found.updatedAt))

        assertTrue(repositories.delete(inserted.id))
        assertNull(repositories.findById(inserted.id))
        assertFalse(repositories.delete(inserted.id))
        assertFailsWith<IllegalStateException> { repositories.update(updated) }
    }

    @Test
    fun `hasActiveRuns only counts non terminal runs of the given repository`() {
        val busy = repositories.insert(repository(name = "busy", token = null))
        val idle = repositories.insert(repository(name = "idle", token = null))
        val other = repositories.insert(repository(name = "other", token = null))
        insertRun(busy.id, "RUNNING")
        insertRun(idle.id, "SUCCESS")
        insertRun(idle.id, "FAILED")
        insertRun(idle.id, "CANCELLED")
        insertRun(other.id, "PENDING")

        assertTrue(repositories.hasActiveRuns(busy.id))
        assertFalse(repositories.hasActiveRuns(idle.id))
        assertTrue(repositories.hasActiveRuns(other.id))
        assertFalse(repositories.hasActiveRuns(UUID.randomUUID()))
    }

    private fun insertRun(repositoryId: UUID, phase: String) {
        jdbcTemplate.update(
            """
            insert into agent_runs (id, kind, repository_id, cr_name, phase, spec, created_at, updated_at)
            values (?, 'CODE_REVIEW', ?, ?, ?, '{}'::jsonb, now(), now())
            """.trimIndent(),
            UUID.randomUUID(), repositoryId, "review-${UUID.randomUUID()}", phase,
        )
    }

    private fun repository(name: String, token: EncryptedToken?): Repository {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        return Repository(
            id = id,
            name = name,
            url = "https://example.org/$name.git",
            token = token,
            secretName = token?.let { "sift-repo-$id" },
            createdAt = now,
            updatedAt = now,
        )
    }
}
