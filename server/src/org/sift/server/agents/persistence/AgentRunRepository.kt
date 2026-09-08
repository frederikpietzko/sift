package org.sift.server.agents.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.RunCreator
import org.sift.server.agents.RunSource
import org.sift.server.api.Page
import org.sift.server.users.persistence.UsersTable
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Blocking Exposed DSL access to `agent_runs`; callers own the Spring transaction. Reads left-join `users` so
 * the creator's username travels with the run (`created_by` is immutable after [insert]). Foreign keys live in
 * the Flyway schema only, hence the explicit join condition.
 */
@org.springframework.stereotype.Repository
@Suppress("TooManyFunctions") // one query per access pattern of `agent_runs`
class AgentRunRepository {
    private val runsWithCreator = AgentRunsTable.join(
        otherTable = UsersTable,
        joinType = JoinType.LEFT,
        onColumn = AgentRunsTable.createdBy,
        otherColumn = UsersTable.id,
    )

    fun insert(run: AgentRun): AgentRun {
        AgentRunsTable.insert {
            it[id] = run.id.toKotlinUuid()
            it[kind] = run.kind.name
            it[runSource] = run.source.name
            it[repositoryId] = run.repositoryId?.toKotlinUuid()
            it[createdBy] = run.createdBy?.id?.toKotlinUuid()
            it[crName] = run.crName
            it[crUid] = run.crUid
            it[generation] = run.generation
            it[executionId] = run.executionId
            it[phase] = run.phase.name
            it[reason] = run.reason
            it[message] = run.message
            it[spec] = run.spec
            it[createdAt] = run.createdAt
            it[startedAt] = run.startedAt
            it[completedAt] = run.completedAt
            it[observedAt] = run.observedAt
            it[updatedAt] = run.updatedAt
        }
        return run
    }

    /** Rewrites every mutable column (everything except `id`, `kind`, `source`, `repository_id`, `created_at`). */
    fun update(run: AgentRun): AgentRun {
        val updated = AgentRunsTable.update({ AgentRunsTable.id eq run.id.toKotlinUuid() }) {
            it[crName] = run.crName
            it[crUid] = run.crUid
            it[generation] = run.generation
            it[executionId] = run.executionId
            it[phase] = run.phase.name
            it[reason] = run.reason
            it[message] = run.message
            it[spec] = run.spec
            it[startedAt] = run.startedAt
            it[completedAt] = run.completedAt
            it[observedAt] = run.observedAt
            it[updatedAt] = run.updatedAt
        }
        check(updated == 1) { "Agent run ${run.id} does not exist" }
        return run
    }

    /** Status-only write used by the event consumer; the CR identity and spec stay untouched. */
    fun updateStatus(run: AgentRun): AgentRun {
        val updated = AgentRunsTable.update({ AgentRunsTable.id eq run.id.toKotlinUuid() }) {
            it[generation] = run.generation
            it[executionId] = run.executionId
            it[phase] = run.phase.name
            it[reason] = run.reason
            it[message] = run.message
            it[startedAt] = run.startedAt
            it[completedAt] = run.completedAt
            it[observedAt] = run.observedAt
            it[updatedAt] = run.updatedAt
        }
        check(updated == 1) { "Agent run ${run.id} does not exist" }
        return run
    }

    /** Removes the run; returns whether a row was deleted. Rows referencing it must be gone already. */
    fun delete(id: UUID): Boolean =
        AgentRunsTable.deleteWhere { AgentRunsTable.id eq id.toKotlinUuid() } == 1

    fun findById(id: UUID): AgentRun? =
        runsWithCreator.selectAll().where { AgentRunsTable.id eq id.toKotlinUuid() }.singleOrNull()?.toAgentRun()

    fun findByCrUid(crUid: String): AgentRun? =
        runsWithCreator.selectAll().where { AgentRunsTable.crUid eq crUid }.singleOrNull()?.toAgentRun()

    /** `execution_id` is not unique; the newest run (by `created_at`) wins should several carry the same one. */
    fun findByExecutionId(executionId: String): AgentRun? = runsWithCreator.selectAll()
        .where { AgentRunsTable.executionId eq executionId }
        .orderBy(AgentRunsTable.createdAt, SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.toAgentRun()

    /** Newest runs first; [page] is zero-based. */
    fun list(filter: AgentRunFilter, page: Int, size: Int): Page<AgentRun> {
        require(page >= 0) { "page must not be negative" }
        require(size > 0) { "size must be positive" }
        val total = filtered(filter).count()
        val items = filtered(filter)
            .orderBy(AgentRunsTable.createdAt, SortOrder.DESC)
            .limit(size)
            .offset(page.toLong() * size)
            .map { it.toAgentRun() }
        return Page(items = items, page = page, size = size, total = total)
    }

    /** Runs written strictly after [since], oldest change first, capped at [limit]; used to catch up a watcher. */
    fun findUpdatedSince(since: OffsetDateTime, filter: AgentRunFilter, limit: Int): List<AgentRun> {
        require(limit > 0) { "limit must be positive" }
        return filtered(filter)
            .andWhere { AgentRunsTable.updatedAt greater since }
            .orderBy(AgentRunsTable.updatedAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toAgentRun() }
    }

    /** True when any run of the repository is still in a non-terminal phase. */
    fun hasActiveRuns(repositoryId: UUID): Boolean = AgentRunsTable
        .select(AgentRunsTable.id)
        .where {
            (AgentRunsTable.repositoryId eq repositoryId.toKotlinUuid()) and
                (AgentRunsTable.phase notInList TERMINAL_PHASES)
        }
        .limit(1)
        .any()

    private fun filtered(filter: AgentRunFilter): Query {
        val query = runsWithCreator.selectAll()
        filter.kind?.let { kind -> query.andWhere { AgentRunsTable.kind eq kind.name } }
        filter.phase?.let { phase -> query.andWhere { AgentRunsTable.phase eq phase.name } }
        filter.repositoryId?.let { id -> query.andWhere { AgentRunsTable.repositoryId eq id.toKotlinUuid() } }
        filter.createdBy?.let { id -> query.andWhere { AgentRunsTable.createdBy eq id.toKotlinUuid() } }
        return query
    }

    companion object {
        val TERMINAL_PHASES: List<String> = AgentPhase.entries.filter { it.terminal }.map { it.name }
    }
}

/** Maps a row of `agent_runs` left-joined with `users` (for the creator's username). */
private fun ResultRow.toAgentRun(): AgentRun = AgentRun(
    id = this[AgentRunsTable.id].toJavaUuid(),
    kind = AgentKind.valueOf(this[AgentRunsTable.kind]),
    source = RunSource.valueOf(this[AgentRunsTable.runSource]),
    repositoryId = this[AgentRunsTable.repositoryId]?.toJavaUuid(),
    crName = this[AgentRunsTable.crName],
    crUid = this[AgentRunsTable.crUid],
    generation = this[AgentRunsTable.generation],
    executionId = this[AgentRunsTable.executionId],
    phase = AgentPhase.valueOf(this[AgentRunsTable.phase]),
    reason = this[AgentRunsTable.reason],
    message = this[AgentRunsTable.message],
    spec = this[AgentRunsTable.spec],
    createdAt = this[AgentRunsTable.createdAt],
    startedAt = this[AgentRunsTable.startedAt],
    completedAt = this[AgentRunsTable.completedAt],
    observedAt = this[AgentRunsTable.observedAt],
    updatedAt = this[AgentRunsTable.updatedAt],
    createdBy = this[AgentRunsTable.createdBy]?.let { userId ->
        RunCreator(id = userId.toJavaUuid(), username = this[UsersTable.username])
    },
)
