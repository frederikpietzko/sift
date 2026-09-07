package org.sift.server.results

import org.sift.events.Severity
import java.time.OffsetDateTime
import java.util.UUID

data class ReviewResultSummaryResponse(
    val id: UUID,
    val executionId: String,
    val agentRunId: UUID?,
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String?,
    val summary: String,
    val findingCount: Long,
    val completedAt: OffsetDateTime,
    val receivedAt: OffsetDateTime,
) {
    companion object {
        fun from(result: ReviewResult, findingCount: Long): ReviewResultSummaryResponse = ReviewResultSummaryResponse(
            id = result.id,
            executionId = result.executionId,
            agentRunId = result.agentRunId,
            repositoryUrl = result.repositoryUrl,
            branch = result.branch,
            baseBranch = result.baseBranch,
            commitSha = result.commitSha,
            pullRequest = result.pullRequest,
            summary = result.summary,
            findingCount = findingCount,
            completedAt = result.completedAt,
            receivedAt = result.receivedAt,
        )
    }
}

data class ReviewResultResponse(
    val id: UUID,
    val executionId: String,
    val agentRunId: UUID?,
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String?,
    val summary: String,
    val findingCount: Long,
    val completedAt: OffsetDateTime,
    val receivedAt: OffsetDateTime,
    val findings: List<ReviewFindingResponse>,
) {
    companion object {
        fun from(result: ReviewResult, findings: List<ReviewFinding>): ReviewResultResponse = ReviewResultResponse(
            id = result.id,
            executionId = result.executionId,
            agentRunId = result.agentRunId,
            repositoryUrl = result.repositoryUrl,
            branch = result.branch,
            baseBranch = result.baseBranch,
            commitSha = result.commitSha,
            pullRequest = result.pullRequest,
            summary = result.summary,
            findingCount = findings.size.toLong(),
            completedAt = result.completedAt,
            receivedAt = result.receivedAt,
            findings = findings.map(ReviewFindingResponse::from),
        )
    }
}

data class ReviewFindingResponse(
    val id: Long,
    val file: String,
    val startLine: Int?,
    val endLine: Int?,
    val severity: Severity,
    val category: String?,
    val message: String,
    val suggestion: String?,
) {
    companion object {
        fun from(finding: ReviewFinding): ReviewFindingResponse = ReviewFindingResponse(
            id = finding.id,
            file = finding.file,
            startLine = finding.startLine,
            endLine = finding.endLine,
            severity = finding.severity,
            category = finding.category,
            message = finding.message,
            suggestion = finding.suggestion,
        )
    }
}
