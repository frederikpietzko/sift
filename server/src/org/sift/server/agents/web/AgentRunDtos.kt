package org.sift.server.agents.web

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.RunSource
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

/**
 * Revised parameters of an existing run. The kind is taken from the run being revised, so it cannot be changed.
 */
data class UpdateAgentRunRequest(
    val repositoryId: UUID,
    @field:NotBlank val branch: String,
    @field:NotBlank val baseBranch: String,
    @field:Pattern(regexp = "^[0-9a-fA-F]{40}$") val commitSha: String,
    val pullRequest: String? = null,
) {
    fun toCreateRequest(kind: AgentKind): CreateAgentRunRequest = CreateAgentRunRequest(
        kind = kind,
        repositoryId = repositoryId,
        branch = branch,
        baseBranch = baseBranch,
        commitSha = commitSha,
        pullRequest = pullRequest,
    )
}

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
    /** Kind-specific run parameters, e.g. [CodeReviewRunSpec] for `CODE_REVIEW`; documented as a free-form object. */
    @field:Schema(
        implementation = Map::class,
        description = "Kind-specific run parameters, e.g. `CodeReviewRunSpec` for `CODE_REVIEW`",
    )
    val spec: JsonNode,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime,
    /** Absent (`null`) for `EXTERNAL` runs. */
    val createdBy: RunCreatorResponse?,
    /** The run this one revises; `null` for runs that were not created by revising another run. */
    val supersedesRunId: UUID?,
    /** The run that revises this one; `null` while this run is the latest revision. */
    val supersededByRunId: UUID?,
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
            supersedesRunId = run.supersedesRunId,
            supersededByRunId = run.supersededByRunId,
        )
    }
}

data class RunCreatorResponse(
    val id: UUID,
    val username: String,
)
