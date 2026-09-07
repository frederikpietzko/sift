package org.sift.server.repositories

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRunsTable
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Blocking Exposed DSL access to `repositories`; callers own the Spring transaction. */
@org.springframework.stereotype.Repository
class RepositoryRepository {
    fun insert(repository: Repository): Repository {
        RepositoriesTable.insert {
            it[id] = repository.id.toKotlinUuid()
            it[name] = repository.name
            it[url] = repository.url
            it[tokenCiphertext] = repository.token?.ciphertext
            it[tokenIv] = repository.token?.iv
            it[secretName] = repository.secretName
            it[createdAt] = repository.createdAt
            it[updatedAt] = repository.updatedAt
        }
        return repository
    }

    fun update(repository: Repository): Repository {
        val updated = RepositoriesTable.update({ RepositoriesTable.id eq repository.id.toKotlinUuid() }) {
            it[name] = repository.name
            it[url] = repository.url
            it[tokenCiphertext] = repository.token?.ciphertext
            it[tokenIv] = repository.token?.iv
            it[secretName] = repository.secretName
            it[updatedAt] = repository.updatedAt
        }
        check(updated == 1) { "Repository ${repository.id} does not exist" }
        return repository
    }

    fun findById(id: UUID): Repository? =
        RepositoriesTable.selectAll().where { RepositoriesTable.id eq id.toKotlinUuid() }.singleOrNull()?.toRepository()

    fun findByName(name: String): Repository? =
        RepositoriesTable.selectAll().where { RepositoriesTable.name eq name }.singleOrNull()?.toRepository()

    fun findAll(): List<Repository> =
        RepositoriesTable.selectAll().orderBy(RepositoriesTable.name, SortOrder.ASC).map { it.toRepository() }

    fun delete(id: UUID): Boolean = RepositoriesTable.deleteWhere { RepositoriesTable.id eq id.toKotlinUuid() } == 1

    /** True when any agent run of the repository is still in a non-terminal phase. */
    fun hasActiveRuns(repositoryId: UUID): Boolean = AgentRunsTable
        .select(AgentRunsTable.id)
        .where {
            (AgentRunsTable.repositoryId eq repositoryId.toKotlinUuid()) and
                (AgentRunsTable.phase notInList TERMINAL_PHASES)
        }
        .limit(1)
        .any()

    private fun ResultRow.toRepository(): Repository {
        val ciphertext = this[RepositoriesTable.tokenCiphertext]
        val iv = this[RepositoriesTable.tokenIv]
        return Repository(
            id = this[RepositoriesTable.id].toJavaUuid(),
            name = this[RepositoriesTable.name],
            url = this[RepositoriesTable.url],
            token = if (ciphertext != null && iv != null) EncryptedToken(ciphertext = ciphertext, iv = iv) else null,
            secretName = this[RepositoriesTable.secretName],
            createdAt = this[RepositoriesTable.createdAt],
            updatedAt = this[RepositoriesTable.updatedAt],
        )
    }

    companion object {
        val TERMINAL_PHASES: List<String> = AgentPhase.entries.filter { it.terminal }.map { it.name }
    }
}
