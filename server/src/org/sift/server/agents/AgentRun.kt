package org.sift.server.agents

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
)

data class AgentRunFilter(
    val kind: AgentKind? = null,
    val phase: AgentPhase? = null,
    val repositoryId: UUID? = null,
)

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)
