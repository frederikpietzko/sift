package org.sift.server.results.messaging

import org.sift.events.CodeReviewCompletedEvent
import org.sift.server.config.ServerQueues
import org.sift.server.results.ReviewResultService
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Feeds `code-review.completed` events into [ReviewResultService.store]. Gated by [ServerQueues.CONSUMERS_ENABLED]
 * like the status consumer; a throwing listener rejects the message, which dead-letters it.
 */
@Component
@ConditionalOnProperty(ServerQueues.CONSUMERS_ENABLED, havingValue = "true", matchIfMissing = true)
class ReviewResultConsumer(private val service: ReviewResultService) {
    @RabbitListener(queues = [ServerQueues.CODE_REVIEW_COMPLETED])
    fun on(event: CodeReviewCompletedEvent) = service.store(event)
}
