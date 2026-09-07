package org.sift.server.repositories

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.sift.server.config.ServerProperties
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RepositoryServiceTest {
    private val repositories = mockk<RepositoryRepository>(relaxed = true)
    private val secrets = mockk<RepositorySecretSync>(relaxed = true)
    private val cipher = TokenCipher(
        ServerProperties(
            encryptionKey = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
                .let(Base64.getEncoder()::encodeToString),
        ),
    )
    private val clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC)
    private val service = RepositoryService(repositories, cipher, secrets, clock)

    init {
        every { secrets.secretName(any()) } answers { "sift-repo-${firstArg<UUID>()}" }
        every { repositories.findByName(any()) } returns null
    }

    @Test
    fun `create encrypts the token persists the row and mirrors the secret`() {
        val saved = slot<Repository>()
        every { repositories.insert(capture(saved)) } answers { saved.captured }

        val created = service.create(name = " sift ", url = "https://github.com/sift/sift.git", token = "ghp_token")

        assertEquals("sift", created.name)
        assertEquals("https://github.com/sift/sift.git", created.url)
        assertEquals("sift-repo-${created.id}", created.secretName)
        assertEquals(OffsetDateTime.now(clock), created.createdAt)
        assertEquals(created.createdAt, created.updatedAt)
        assertEquals("ghp_token", cipher.decrypt(assertNotNull(created.token)))
        assertSame(created, saved.captured)
        verify(exactly = 1) { secrets.apply(created.id, "ghp_token") }
    }

    @Test
    fun `create without token stores no ciphertext and touches no secret`() {
        every { repositories.insert(any()) } answers { firstArg() }
        listOf(null, "", "  ").forEach { token ->
            val created = service.create(name = "sift-$token", url = "https://example.org/repo", token = token)
            assertNull(created.token)
            assertNull(created.secretName)
            assertFalse(created.hasToken)
        }
        verify(exactly = 0) { secrets.apply(any(), any()) }
    }

    @Test
    fun `create rejects invalid URLs and blank names before touching storage`() {
        listOf(
            "",
            "   ",
            "not a url",
            "ftp://example.org/repo",
            "https://user:secret@example.org/repo",
            "https://example.org/repo?token=x",
            "https://example.org/repo#frag",
            "https://",
            "https://example.org/\${TOKEN}",
            "git@github.com:sift/sift.git",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { service.create("sift", url, null) }
        }
        assertFailsWith<IllegalArgumentException> { service.create("  ", "https://example.org/repo", null) }
        verify(exactly = 0) { repositories.insert(any()) }
        verify(exactly = 0) { secrets.apply(any(), any()) }
    }

    @Test
    fun `create reports a duplicate name as conflict`() {
        every { repositories.findByName("sift") } returns repository(token = null)
        assertFailsWith<ConflictException> { service.create("sift", "https://example.org/repo", "t") }
        verify(exactly = 0) { repositories.insert(any()) }
    }

    @Test
    fun `update rotates the token and secret and keeps the url when omitted`() {
        val existing = repository(token = cipher.encrypt("old"))
        every { repositories.findById(existing.id) } returns existing
        every { repositories.update(any()) } answers { firstArg() }

        val updated = service.update(existing.id, url = null, token = "new")

        assertEquals(existing.url, updated.url)
        assertEquals(existing.createdAt, updated.createdAt)
        assertEquals(OffsetDateTime.now(clock), updated.updatedAt)
        assertEquals("new", cipher.decrypt(assertNotNull(updated.token)))
        assertEquals("sift-repo-${existing.id}", updated.secretName)
        verify(exactly = 1) { secrets.apply(existing.id, "new") }
        verify(exactly = 0) { secrets.delete(any()) }
    }

    @Test
    fun `update with only a url keeps the existing token and does not touch the secret`() {
        val existing = repository(token = cipher.encrypt("old"))
        every { repositories.findById(existing.id) } returns existing
        every { repositories.update(any()) } answers { firstArg() }

        val updated = service.update(existing.id, url = "https://example.org/moved", token = null)

        assertEquals("https://example.org/moved", updated.url)
        assertSame(existing.token, updated.token)
        assertEquals(existing.secretName, updated.secretName)
        verify(exactly = 0) { secrets.apply(any(), any()) }
        verify(exactly = 0) { secrets.delete(any()) }
        assertFailsWith<IllegalArgumentException> { service.update(existing.id, url = "nope", token = null) }
    }

    @Test
    fun `update can clear the token which deletes the secret`() {
        val existing = repository(token = cipher.encrypt("old"))
        every { repositories.findById(existing.id) } returns existing
        every { repositories.update(any()) } answers { firstArg() }

        val updated = service.update(existing.id, url = null, token = null, clearToken = true)

        assertNull(updated.token)
        assertNull(updated.secretName)
        verify(exactly = 1) { secrets.delete(existing.id) }
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.id, url = null, token = "x", clearToken = true)
        }
    }

    @Test
    fun `get list and secretRef expose stored state without plaintext`() {
        val withToken = repository(token = cipher.encrypt("t"))
        val withoutToken = repository(name = "other", token = null)
        every { repositories.findById(withToken.id) } returns withToken
        every { repositories.findById(withoutToken.id) } returns withoutToken
        every { repositories.findAll() } returns listOf(withoutToken, withToken)

        assertSame(withToken, service.get(withToken.id))
        assertEquals(listOf(withoutToken, withToken), service.list())
        val ref = assertNotNull(service.secretRef(withToken.id))
        assertEquals("sift-repo-${withToken.id}", ref.name)
        assertEquals("token", ref.key)
        assertNull(service.secretRef(withoutToken.id))
        assertTrue(RepositoryService::class.java.declaredMethods.none { it.name.contains("decrypt", ignoreCase = true) })
    }

    @Test
    fun `missing repositories produce not found`() {
        val id = UUID.randomUUID()
        every { repositories.findById(id) } returns null
        assertFailsWith<NotFoundException> { service.get(id) }
        assertFailsWith<NotFoundException> { service.update(id, "https://example.org/x", null) }
        assertFailsWith<NotFoundException> { service.delete(id) }
        assertFailsWith<NotFoundException> { service.secretRef(id) }
    }

    @Test
    fun `delete removes secret then row and refuses while runs are active`() {
        val existing = repository(token = cipher.encrypt("t"))
        every { repositories.findById(existing.id) } returns existing
        every { repositories.hasActiveRuns(existing.id) } returns true
        assertFailsWith<ConflictException> { service.delete(existing.id) }
        verify(exactly = 0) { secrets.delete(any()) }
        verify(exactly = 0) { repositories.delete(any()) }

        every { repositories.hasActiveRuns(existing.id) } returns false
        service.delete(existing.id)
        verify(exactly = 1) { secrets.delete(existing.id) }
        verify(exactly = 1) { repositories.delete(existing.id) }
    }

    private fun repository(name: String = "sift", token: EncryptedToken?): Repository {
        val id = UUID.randomUUID()
        return Repository(
            id = id,
            name = name,
            url = "https://example.org/$name",
            token = token,
            secretName = token?.let { "sift-repo-$id" },
            createdAt = OffsetDateTime.parse("2026-09-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-09-01T00:00:00Z"),
        )
    }
}

private const val KEY_BYTES = 32
