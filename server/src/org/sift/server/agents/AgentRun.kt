package org.sift.server.agents

import org.sift.server.users.User
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

enum class AgentKind {
    CODE_REVIEW,
}

/** Lifecycle of an agent run; mirrors the CR phases plus the server-only `CANCELLED`. */
enum class AgentPhase(val terminal: Boolean) {
    CREATED(terminal = false),
    PENDING(terminal = false),
    RUNNING(terminal = false),
    SUCCESS(terminal = true),
    FAILED(terminal = true),
    CANCELLED(terminal = true),
    ;

    companion object {
        /** Lenient counterpart of [valueOf] for phases carried as plain strings in events. */
        fun fromName(name: String): AgentPhase? = entries.firstOrNull { it.name == name }
    }
}

/** `API` runs were requested through the server; `EXTERNAL` runs were first seen via a status event. */
enum class RunSource {
    API,
    EXTERNAL,
}

data class AgentRun(
    val id: UUID,
    val kind: AgentKind,
    val source: RunSource,
    val repositoryId: UUID?,
    val crName: String,
    val crUid: String?,
    val generation: Long?,
    val executionId: String?,
    val phase: AgentPhase,
    val reason: String?,
    val message: String?,
    val spec: JsonNode,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val observedAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime,
    /** The user who requested the run through the API; `null` for `EXTERNAL` runs. */
    val createdBy: RunCreator? = null,
    /** The run this one revises; `null` unless the run was created through the revise endpoint. */
    val supersedesRunId: UUID? = null,
    /** Derived by lookup, never persisted: the run that revises this one. */
    val supersededByRunId: UUID? = null,
)

/** Denormalised view of the `users` row behind `agent_runs.created_by`. */
data class RunCreator(
    val id: UUID,
    val username: String,
)

data class AgentRunFilter(
    val kind: AgentKind? = null,
    val phase: AgentPhase? = null,
    val repositoryId: UUID? = null,
    val createdBy: UUID? = null,
) {
    companion object {
        /**
         * Resolves the `mine` / `createdBy` query parameters to a creator id: `mine=true` means the caller;
         * combining it with a `createdBy` that names somebody else is a contradiction and rejected (`400`).
         */
        fun resolveCreatedBy(mine: Boolean, createdBy: UUID?, user: User): UUID? {
            if (!mine) return createdBy
            require(createdBy == null || createdBy == user.id) {
                "mine=true cannot be combined with createdBy=$createdBy"
            }
            return user.id
        }
    }
}
