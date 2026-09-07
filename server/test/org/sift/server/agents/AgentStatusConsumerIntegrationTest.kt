package org.sift.server.agents

import org.awaitility.Awaitility.await
import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.messaging.EventPublisher
import org.sift.server.RabbitMqIntegrationTest
import org.sift.server.config.ServerQueues
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Full path from the broker to Postgres for `code-review.status`. */
class AgentStatusConsumerIntegrationTest : RabbitMqIntegrationTest() {
    @Autowired
    private lateinit var publisher: EventPublisher

    @Autowired
    private lateinit var rabbitAdmin: RabbitAdmin

    @Autowired
    private lateinit var runs: AgentRunRepository

    @Autowired
    private lateinit var transactions: TransactionOperations

    @Test
    fun `server queues and dead letter topology are declared`() {
        listOf(ServerQueues.CODE_REVIEW_STATUS, ServerQueues.CODE_REVIEW_COMPLETED, ServerQueues.DEAD_LETTER)
            .forEach { queue -> assertNotNull(rabbitAdmin.getQueueInfo(queue), queue) }
        val properties = assertNotNull(rabbitAdmin.getQueueProperties(ServerQueues.CODE_REVIEW_STATUS))
        assertEquals(ServerQueues.CODE_REVIEW_STATUS, properties[RabbitAdmin.QUEUE_NAME])
    }

    @Test
    fun `a published status event is consumed and upserted as an EXTERNAL run`() {
        val uid = UUID.randomUUID().toString()
        publisher.publish(
            event(uid = uid, phase = "RUNNING", generation = 1, observedAt = Instant.parse("2026-09-07T10:00:00Z")),
        )

        val run = await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until({ findByUid(uid) }) { it != null }
        assertNotNull(run)
        assertEquals(RunSource.EXTERNAL, run.source)
        assertEquals(AgentKind.CODE_REVIEW, run.kind)
        assertEquals(AgentPhase.RUNNING, run.phase)
        assertEquals("review-$uid", run.crName)
        assertNull(run.repositoryId)
        assertEquals("https://example.org/ext.git", run.spec["repositoryUrl"].asString())

        publisher.publish(
            event(uid = uid, phase = "SUCCESS", generation = 1, observedAt = Instant.parse("2026-09-07T10:05:00Z")),
        )
        val finished = await().atMost(Duration.ofSeconds(WAIT_SECONDS))
            .until({ findByUid(uid) }) { it?.phase == AgentPhase.SUCCESS }
        assertEquals(Instant.parse("2026-09-07T10:05:00Z"), assertNotNull(finished?.observedAt).toInstant())
    }

    private fun findByUid(uid: String): AgentRun? = transactions.execute { runs.findByCrUid(uid) }

    private fun event(
        uid: String,
        phase: String,
        generation: Long,
        observedAt: Instant,
    ): CodeReviewStatusChangedEvent = CodeReviewStatusChangedEvent(
        reviewName = "review-$uid",
        reviewNamespace = "sift-dev",
        reviewUid = uid,
        generation = generation,
        executionId = "$uid:$generation",
        repositoryUrl = "https://example.org/ext.git",
        branch = "feature/ext",
        baseBranch = "main",
        commitSha = "b".repeat(SHA_LENGTH),
        pullRequest = null,
        phase = phase,
        reason = null,
        message = null,
        startedAt = observedAt,
        completedAt = null,
        observedAt = observedAt,
    )

    companion object {
        private const val SHA_LENGTH = 40
        private const val WAIT_SECONDS = 15L
    }
}
