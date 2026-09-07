package org.sift.server.results

import org.awaitility.Awaitility.await
import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.Finding
import org.sift.events.Severity
import org.sift.messaging.EventPublisher
import org.sift.server.RabbitMqIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Full path from the broker to Postgres for `code-review.completed`, including idempotent redelivery. */
class ReviewResultConsumerIntegrationTest : RabbitMqIntegrationTest() {
    @Autowired
    private lateinit var publisher: EventPublisher

    @Autowired
    private lateinit var results: ReviewResultRepository

    @Autowired
    private lateinit var transactions: TransactionOperations

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `a published completed event is stored once with its findings even when delivered twice`() {
        val executionId = "exec-${UUID.randomUUID()}"
        val event = event(executionId)

        publisher.publish(event)
        val stored = await().atMost(Duration.ofSeconds(WAIT_SECONDS))
            .until({ findByExecutionId(executionId) }) { it != null }
        assertNotNull(stored)
        assertNull(stored.agentRunId)
        assertEquals("https://example.org/completed.git", stored.repositoryUrl)
        assertEquals("all good", stored.summary)
        assertEquals(Instant.parse("2026-09-07T10:00:00Z"), stored.completedAt.toInstant())
        val findings = await().atMost(Duration.ofSeconds(WAIT_SECONDS))
            .until({ findings(stored.id) }) { it.size == 2 }
        assertEquals(listOf("a.kt", "b.kt"), findings.map { it.file })
        assertEquals(listOf(Severity.MAJOR, Severity.INFO), findings.map { it.severity })

        // The sentinel is queued behind the duplicate, so once it has landed the duplicate has been processed too.
        val sentinel = "exec-${UUID.randomUUID()}"
        publisher.publish(event.copy(summary = "duplicate delivery"))
        publisher.publish(event(executionId = sentinel))
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { findByExecutionId(sentinel) != null }

        assertEquals(1, countResults(executionId))
        assertEquals("all good", assertNotNull(findByExecutionId(executionId)).summary)
        assertEquals(2, findings(stored.id).size)
    }

    private fun findByExecutionId(executionId: String): ReviewResult? =
        transactions.execute { results.findByExecutionId(executionId) }

    private fun findings(resultId: UUID): List<ReviewFinding> =
        transactions.execute { results.findingsByResultId(resultId) }

    private fun countResults(executionId: String): Int = assertNotNull(
        jdbcTemplate.queryForObject(
            "select count(*) from review_results where execution_id = ?",
            Int::class.java,
            executionId,
        ),
    )

    private fun event(executionId: String): CodeReviewCompletedEvent = CodeReviewCompletedEvent(
        repositoryUrl = "https://example.org/completed.git",
        branch = "feature/done",
        baseBranch = "main",
        commitSha = "c".repeat(SHA_LENGTH),
        executionId = executionId,
        pullRequest = "7",
        summary = "all good",
        findings = listOf(
            Finding("a.kt", 1, 2, Severity.MAJOR, "style", "rename", "call it x"),
            Finding("b.kt", null, null, Severity.INFO, null, "nit", null),
        ),
        completedAt = Instant.parse("2026-09-07T10:00:00Z"),
    )

    companion object {
        private const val SHA_LENGTH = 40
        private const val WAIT_SECONDS = 15L
    }
}
