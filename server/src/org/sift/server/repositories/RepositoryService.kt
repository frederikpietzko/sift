package org.sift.server.repositories

import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Reference to the k8s Secret key holding a repository token, consumed by the `CodeReview` CR builder. */
class SecretRef(val name: String, val key: String = RepositorySecretSync.TOKEN_KEY)

@Service
@Transactional
class RepositoryService(
    private val repositories: RepositoryRepository,
    private val cipher: TokenCipher,
    private val secrets: RepositorySecretSync,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(name: String, url: String, token: String?): Repository {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Repository name must not be blank" }
        validateUrl(url)
        if (repositories.findByName(trimmedName) != null) {
            throw ConflictException("Repository '$trimmedName' already exists")
        }
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        val plain = token?.takeUnless(String::isBlank)
        val repository = Repository(
            id = id,
            name = trimmedName,
            url = url,
            token = plain?.let(cipher::encrypt),
            secretName = plain?.let { secrets.secretName(id) },
            createdAt = now,
            updatedAt = now,
        )
        repositories.insert(repository)
        plain?.let { secrets.apply(id, it) }
        return repository
    }

    fun update(id: UUID, url: String?, token: String?, clearToken: Boolean = false): Repository {
        val existing = find(id)
        url?.let(::validateUrl)
        val plain = token?.takeUnless(String::isBlank)
        require(!(clearToken && plain != null)) { "Cannot both clear and rotate the token" }
        val updated = Repository(
            id = existing.id,
            name = existing.name,
            url = url ?: existing.url,
            token = when {
                clearToken -> null
                plain != null -> cipher.encrypt(plain)
                else -> existing.token
            },
            secretName = when {
                clearToken -> null
                plain != null -> secrets.secretName(id)
                else -> existing.secretName
            },
            createdAt = existing.createdAt,
            updatedAt = OffsetDateTime.now(clock),
        )
        repositories.update(updated)
        when {
            clearToken -> secrets.delete(id)
            plain != null -> secrets.apply(id, plain)
        }
        return updated
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Repository = find(id)

    @Transactional(readOnly = true)
    fun list(): List<Repository> = repositories.findAll()

    /** Where the CR builder finds the token; `null` when the repository has no credentials configured. */
    @Transactional(readOnly = true)
    fun secretRef(id: UUID): SecretRef? = find(id).secretName?.let { SecretRef(name = it) }

    fun delete(id: UUID) {
        find(id)
        if (repositories.hasActiveRuns(id)) {
            throw ConflictException("Repository $id still has active agent runs")
        }
        secrets.delete(id)
        repositories.delete(id)
    }

    private fun find(id: UUID): Repository =
        repositories.findById(id) ?: throw NotFoundException("Repository $id not found")

    private fun validateUrl(url: String) {
        require(url.isNotBlank() && !url.contains(Regex("\\s")) && !url.contains("\${")) {
            "Repository URL must be a nonblank literal without whitespace or placeholders"
        }
        val uri = runCatching { URI(url) }.getOrElse { exception ->
            throw IllegalArgumentException("Repository URL is not a valid URI", exception)
        }
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Repository URL must be an HTTP(S) URL without embedded credentials, query or fragment"
        }
    }
}
