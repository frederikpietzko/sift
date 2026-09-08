package org.sift.server.repositories.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.sift.server.repositories.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class CreateRepositoryRequest(
    @field:NotBlank @field:Size(max = MAX_NAME_LENGTH) val name: String,
    @field:NotBlank val url: String,
    val token: String? = null,
)

data class UpdateRepositoryRequest(
    val url: String? = null,
    val token: String? = null,
    val clearToken: Boolean = false,
)

/** Public view of a repository; the token never leaves the server. */
data class RepositoryResponse(
    val id: UUID,
    val name: String,
    val url: String,
    val hasToken: Boolean,
    val secretName: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(repository: Repository): RepositoryResponse = RepositoryResponse(
            id = repository.id,
            name = repository.name,
            url = repository.url,
            hasToken = repository.hasToken,
            secretName = repository.secretName,
            createdAt = repository.createdAt,
            updatedAt = repository.updatedAt,
        )
    }
}

private const val MAX_NAME_LENGTH = 100
