package org.sift.server.results

import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.Severity
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRunRepository
import org.sift.server.agents.Page
import org.sift.server.api.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Ingests `code-review.completed` events and serves the stored results. Ingestion is idempotent on
 * `executionId`; a result whose run is still non-terminal completes that run as `SUCCESS`, because the
 * agent has evidently finished even if the operator's final status event is late or lost.
 */
@Service
class ReviewResultService(
    private val results: ReviewResultRepository,
    private val runs: AgentRunRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ReviewResultService::class.java)

    @Transactional
    fun store(event: CodeReviewCompletedEvent) {
        val run = runs.findByExecutionId(event.executionId)
        val now = OffsetDateTime.now(clock)
        val result = ReviewResult(
            id = UUID.randomUUID(),
            executionId = event.executionId,
            agentRunId = run?.id,
            repositoryUrl = event.repositoryUrl,
            branch = event.branch,
            baseBranch = event.baseBranch,
            commitSha = event.commitSha,
            pullRequest = event.pullRequest,
            summary = event.summary,
            completedAt = event.completedAt.atOffset(ZoneOffset.UTC),
            receivedAt = now,
        )
        if (!results.insert(result, event.findings)) {
            log.debug("Result for execution {} already stored; ignoring duplicate event", event.executionId)
            return
        }
        if (run != null && !run.phase.terminal) {
            runs.updateStatus(
                run.copy(
                    phase = AgentPhase.SUCCESS,
                    reason = REASON_RESULT_RECEIVED,
                    message = null,
                    completedAt = result.completedAt,
                    observedAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): ReviewResult = find(id)

    @Transactional(readOnly = true)
    fun list(filter: ReviewResultFilter, page: Int, size: Int): Page<ReviewResult> =
        results.list(filter, page.coerceAtLeast(0), size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE))

    /** Finding counts for the given results; results without findings map to `0`. */
    @Transactional(readOnly = true)
    fun findingCounts(resultIds: Collection<UUID>): Map<UUID, Long> {
        val counted = results.countFindings(resultIds)
        return resultIds.associateWith { counted[it] ?: 0L }
    }

    @Transactional(readOnly = true)
    fun findings(id: UUID, severity: Severity? = null, file: String? = null): List<ReviewFinding> {
        find(id)
        return results.findings(id, severity, file)
    }

    private fun find(id: UUID): ReviewResult =
        results.findById(id) ?: throw NotFoundException("Review result $id not found")

    companion object {
        const val REASON_RESULT_RECEIVED = "ResultReceived"
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 200
    }
}
