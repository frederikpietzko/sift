package org.sift.server.results

import org.sift.events.Severity
import java.time.OffsetDateTime
import java.util.UUID

/** One `code-review.completed` event persisted; `executionId` is the idempotency key. */
data class ReviewResult(
    val id: UUID,
    val executionId: String,
    val agentRunId: UUID?,
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String?,
    val summary: String,
    val completedAt: OffsetDateTime,
    val receivedAt: OffsetDateTime,
)

data class ReviewFinding(
    val id: Long,
    val resultId: UUID,
    val file: String,
    val startLine: Int?,
    val endLine: Int?,
    val severity: Severity,
    val category: String?,
    val message: String,
    val suggestion: String?,
)

data class ReviewResultFilter(
    val repositoryUrl: String? = null,
    val commitSha: String? = null,
    val agentRunId: UUID? = null,
)
