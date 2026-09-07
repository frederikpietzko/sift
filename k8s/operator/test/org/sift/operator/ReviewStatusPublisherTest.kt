package org.sift.operator

import io.fabric8.kubernetes.api.model.ConditionBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.messaging.EventPublisher
import org.springframework.amqp.AmqpConnectException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewStatusPublisherTest {
    private val now = Instant.parse("2026-09-07T10:00:00Z")
    private val eventPublisher = mockk<EventPublisher>(relaxUnitFun = true)
    private val publisher = ReviewStatusPublisher(eventPublisher, Clock.fixed(now, ZoneOffset.UTC))
    private val review = reviewFixture().apply { metadata.generation = 3L }
    private val status = CodeReview.Status(
        phase = Phase.SUCCESS,
        message = "Review Job completed successfully",
        observedGeneration = 3L,
        executionId = "${review.metadata.uid}:3",
        commitSha = review.spec.commitSha,
        startedAt = "2026-09-07T09:58:00Z",
        completedAt = "2026-09-07T09:59:30Z",
        conditions = listOf(ConditionBuilder().withType("Ready").withStatus("True").withReason("Completed").build()),
    )

    @Test
    fun `event mirrors the resource identity spec and persisted status`() {
        val event = slot<CodeReviewStatusChangedEvent>()
        publisher.publish(review, status)
        verify(exactly = 1) { eventPublisher.publish(capture(event)) }
        val expected = CodeReviewStatusChangedEvent(
            reviewName = "review.example",
            reviewNamespace = "sift-test",
            reviewUid = review.metadata.uid,
            generation = 3L,
            executionId = "${review.metadata.uid}:3",
            repositoryUrl = "https://github.com/example/repository.git",
            branch = "feature/review",
            baseBranch = "main",
            commitSha = "a".repeat(40),
            pullRequest = "1",
            phase = "SUCCESS",
            reason = "Completed",
            message = "Review Job completed successfully",
            startedAt = Instant.parse("2026-09-07T09:58:00Z"),
            completedAt = Instant.parse("2026-09-07T09:59:30Z"),
            observedAt = now,
        )
        assertEquals(expected, event.captured)
        assertEquals(CodeReviewStatusChangedEvent.ROUTING_KEY, event.captured.routingKey)
    }

    @Test
    fun `missing spec and unparsable timestamps degrade to empty values instead of failing`() {
        review.spec = null
        val event = publisher.event(review, status.copy(startedAt = "yesterday", completedAt = null, phase = null))
        assertEquals("", event.repositoryUrl)
        assertEquals("", event.commitSha)
        assertNull(event.pullRequest)
        assertNull(event.startedAt)
        assertNull(event.completedAt)
        assertEquals("CREATED", event.phase)
    }

    @Test
    fun `broker failures are swallowed and logged`() {
        every { eventPublisher.publish(any()) } throws AmqpConnectException(RuntimeException("broker unavailable"))
        publisher.publish(review, status)
        verify(exactly = 1) { eventPublisher.publish(any()) }
    }
}
