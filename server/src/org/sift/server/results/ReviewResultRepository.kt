package org.sift.server.results

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.sift.events.Finding
import org.sift.events.Severity
import org.sift.server.agents.Page
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Blocking Exposed DSL access to `review_results` and `review_findings`; callers own the Spring transaction. */
@org.springframework.stereotype.Repository
class ReviewResultRepository {
    /**
     * Inserts the result with `ON CONFLICT DO NOTHING` on the unique `execution_id` and its findings only when
     * the result row was actually written. Returns whether this call persisted the result.
     */
    fun insert(result: ReviewResult, findings: List<Finding>): Boolean {
        val inserted = ReviewResultsTable.insertIgnore {
            it[id] = result.id.toKotlinUuid()
            it[executionId] = result.executionId
            it[agentRunId] = result.agentRunId?.toKotlinUuid()
            it[repositoryUrl] = result.repositoryUrl
            it[branch] = result.branch
            it[baseBranch] = result.baseBranch
            it[commitSha] = result.commitSha
            it[pullRequest] = result.pullRequest
            it[summary] = result.summary
            it[completedAt] = result.completedAt
            it[receivedAt] = result.receivedAt
        }.insertedCount == 1
        if (inserted && findings.isNotEmpty()) {
            ReviewFindingsTable.batchInsert(findings, shouldReturnGeneratedValues = false) { finding ->
                this[ReviewFindingsTable.resultId] = result.id.toKotlinUuid()
                this[ReviewFindingsTable.file] = finding.file
                this[ReviewFindingsTable.startLine] = finding.startLine
                this[ReviewFindingsTable.endLine] = finding.endLine
                this[ReviewFindingsTable.severity] = finding.severity.name
                this[ReviewFindingsTable.category] = finding.category
                this[ReviewFindingsTable.message] = finding.message
                this[ReviewFindingsTable.suggestion] = finding.suggestion
            }
        }
        return inserted
    }

    fun findById(id: UUID): ReviewResult? = ReviewResultsTable.selectAll()
        .where { ReviewResultsTable.id eq id.toKotlinUuid() }
        .singleOrNull()
        ?.toReviewResult()

    fun findByExecutionId(executionId: String): ReviewResult? = ReviewResultsTable.selectAll()
        .where { ReviewResultsTable.executionId eq executionId }
        .singleOrNull()
        ?.toReviewResult()

    /** Most recently completed results first; [page] is zero-based. */
    fun list(filter: ReviewResultFilter, page: Int, size: Int): Page<ReviewResult> {
        require(page >= 0) { "page must not be negative" }
        require(size > 0) { "size must be positive" }
        val total = filtered(filter).count()
        val items = filtered(filter)
            .orderBy(ReviewResultsTable.completedAt, SortOrder.DESC)
            .limit(size)
            .offset(page.toLong() * size)
            .map { it.toReviewResult() }
        return Page(items = items, page = page, size = size, total = total)
    }

    fun findingsByResultId(resultId: UUID): List<ReviewFinding> = findings(resultId, severity = null, file = null)

    /** Findings of one result in insertion order; [file] is an exact match. */
    fun findings(resultId: UUID, severity: Severity?, file: String?): List<ReviewFinding> {
        val query = ReviewFindingsTable.selectAll().where { ReviewFindingsTable.resultId eq resultId.toKotlinUuid() }
        severity?.let { query.andWhere { ReviewFindingsTable.severity eq it.name } }
        file?.let { query.andWhere { ReviewFindingsTable.file eq it } }
        return query.orderBy(ReviewFindingsTable.id, SortOrder.ASC).map { it.toReviewFinding() }
    }

    /** Number of findings per result id; results without findings are absent from the map. */
    fun countFindings(resultIds: Collection<UUID>): Map<UUID, Long> {
        if (resultIds.isEmpty()) {
            return emptyMap()
        }
        val count = ReviewFindingsTable.id.count()
        return ReviewFindingsTable
            .select(ReviewFindingsTable.resultId, count)
            .where { ReviewFindingsTable.resultId inList resultIds.map { it.toKotlinUuid() } }
            .groupBy(ReviewFindingsTable.resultId)
            .associate { it[ReviewFindingsTable.resultId].toJavaUuid() to it[count] }
    }

    private fun filtered(filter: ReviewResultFilter): Query {
        val query = ReviewResultsTable.selectAll()
        filter.repositoryUrl?.let { url -> query.andWhere { ReviewResultsTable.repositoryUrl eq url } }
        filter.commitSha?.let { sha -> query.andWhere { ReviewResultsTable.commitSha eq sha } }
        filter.agentRunId?.let { id -> query.andWhere { ReviewResultsTable.agentRunId eq id.toKotlinUuid() } }
        return query
    }

    private fun ResultRow.toReviewResult(): ReviewResult = ReviewResult(
        id = this[ReviewResultsTable.id].toJavaUuid(),
        executionId = this[ReviewResultsTable.executionId],
        agentRunId = this[ReviewResultsTable.agentRunId]?.toJavaUuid(),
        repositoryUrl = this[ReviewResultsTable.repositoryUrl],
        branch = this[ReviewResultsTable.branch],
        baseBranch = this[ReviewResultsTable.baseBranch],
        commitSha = this[ReviewResultsTable.commitSha],
        pullRequest = this[ReviewResultsTable.pullRequest],
        summary = this[ReviewResultsTable.summary],
        completedAt = this[ReviewResultsTable.completedAt],
        receivedAt = this[ReviewResultsTable.receivedAt],
    )

    private fun ResultRow.toReviewFinding(): ReviewFinding = ReviewFinding(
        id = this[ReviewFindingsTable.id],
        resultId = this[ReviewFindingsTable.resultId].toJavaUuid(),
        file = this[ReviewFindingsTable.file],
        startLine = this[ReviewFindingsTable.startLine],
        endLine = this[ReviewFindingsTable.endLine],
        severity = Severity.valueOf(this[ReviewFindingsTable.severity]),
        category = this[ReviewFindingsTable.category],
        message = this[ReviewFindingsTable.message],
        suggestion = this[ReviewFindingsTable.suggestion],
    )
}
