package org.sift.server.results

import org.sift.events.CodeReviewCompletedEvent
import org.sift.server.agents.AgentStatusConsumer
import org.sift.server.config.ServerQueues
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Feeds `code-review.completed` events into [ReviewResultService.store]. Gated by the same property as
 * [AgentStatusConsumer]; a throwing listener rejects the message, which dead-letters it.
 */
@Component
@ConditionalOnProperty(AgentStatusConsumer.CONSUMERS_ENABLED, havingValue = "true", matchIfMissing = true)
class ReviewResultConsumer(private val service: ReviewResultService) {
    @RabbitListener(queues = [ServerQueues.CODE_REVIEW_COMPLETED])
    fun on(event: CodeReviewCompletedEvent) = service.store(event)
}
