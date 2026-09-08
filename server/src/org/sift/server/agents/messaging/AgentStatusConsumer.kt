package org.sift.server.agents.messaging

import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.server.agents.AgentRunService
import org.sift.server.config.ServerQueues
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Feeds `code-review.status` events into [AgentRunService.applyStatus]. The payload type is inferred from
 * the parameter by the shared `JacksonJsonMessageConverter`, so the publisher's `__TypeId__` header is not
 * trusted for class loading. A throwing listener rejects the message, which dead-letters it (see
 * `MessagingConfiguration`). Gated by [ServerQueues.CONSUMERS_ENABLED].
 */
@Component
@ConditionalOnProperty(ServerQueues.CONSUMERS_ENABLED, havingValue = "true", matchIfMissing = true)
class AgentStatusConsumer(private val service: AgentRunService) {
    @RabbitListener(queues = [ServerQueues.CODE_REVIEW_STATUS])
    fun on(event: CodeReviewStatusChangedEvent) = service.applyStatus(event)
}
