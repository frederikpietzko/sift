package org.sift.server.agents

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

data class CreateAgentRunRequest(
    val kind: AgentKind,
    val repositoryId: UUID,
    @field:NotBlank val branch: String,
    @field:NotBlank val baseBranch: String,
    @field:Pattern(regexp = "^[0-9a-fA-F]{40}$") val commitSha: String,
    val pullRequest: String? = null,
)

/** What is persisted in `agent_runs.spec` for a `CODE_REVIEW` run. */
data class CodeReviewRunSpec(
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String?,
)

data class AgentRunResponse(
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
    val updatedAt: OffsetDateTime,
    /** Absent (`null`) for `EXTERNAL` runs. */
    val createdBy: RunCreatorResponse?,
) {
    companion object {
        fun from(run: AgentRun): AgentRunResponse = AgentRunResponse(
            id = run.id,
            kind = run.kind,
            source = run.source,
            repositoryId = run.repositoryId,
            crName = run.crName,
            crUid = run.crUid,
            generation = run.generation,
            executionId = run.executionId,
            phase = run.phase,
            reason = run.reason,
            message = run.message,
            spec = run.spec,
            createdAt = run.createdAt,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            updatedAt = run.updatedAt,
            createdBy = run.createdBy?.let { RunCreatorResponse(id = it.id, username = it.username) },
        )
    }
}

data class RunCreatorResponse(
    val id: UUID,
    val username: String,
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T, R> from(page: Page<T>, transform: (T) -> R): PageResponse<R> = PageResponse(
            items = page.items.map(transform),
            page = page.page,
            size = page.size,
            total = page.total,
        )
    }
}
