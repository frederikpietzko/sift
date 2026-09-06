package org.sift.agents.shared.messaging

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sift.events.CodeReviewCompletedEvent
import org.springframework.amqp.AmqpConnectException
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Instant
import kotlin.test.assertEquals

class RabbitEventPublisherTest {
    private val rabbitTemplate = mockk<RabbitTemplate>(relaxUnitFun = true)
    private val publisher = RabbitEventPublisher(rabbitTemplate)

    @Test
    fun `publish sends the event to the sift events exchange under its routing key`() {
        val event = codeReviewCompletedEvent()

        publisher.publish(event)

        verify(exactly = 1) {
            rabbitTemplate.convertAndSend(
                "sift.events",
                CodeReviewCompletedEvent.ROUTING_KEY,
                event,
            )
        }
    }

    @Test
    fun `publish propagates exceptions thrown by the template`() {
        val event = codeReviewCompletedEvent()
        every {
            rabbitTemplate.convertAndSend("sift.events", event.routingKey, any<Any>())
        } throws AmqpConnectException(RuntimeException("broker unavailable"))

        val exception = assertThrows<AmqpConnectException> { publisher.publish(event) }

        assertEquals("broker unavailable", exception.cause?.message)
    }

    private fun codeReviewCompletedEvent(): CodeReviewCompletedEvent =
        CodeReviewCompletedEvent(
            repositoryUrl = "https://example.com/org/repo.git",
            branch = "feature/messaging",
            baseBranch = "main",
            commitSha = "0123456789abcdef0123456789abcdef01234567",
            executionId = "review-uid:1",
            pullRequest = "42",
            summary = "Looks good overall.",
            findings = emptyList(),
            completedAt = Instant.parse("2026-09-05T00:00:00Z"),
        )
}
