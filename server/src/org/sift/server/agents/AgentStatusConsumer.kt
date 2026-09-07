package org.sift.server.agents

import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.server.config.ServerQueues
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Feeds `code-review.status` events into [AgentRunService.applyStatus]. The payload type is inferred from
 * the parameter by the shared `JacksonJsonMessageConverter`, so the publisher's `__TypeId__` header is not
 * trusted for class loading. A throwing listener rejects the message, which dead-letters it (see
 * `MessagingConfiguration`).
 *
 * `sift.server.messaging.consumers-enabled=false` removes the listener entirely; unlike
 * `spring.rabbitmq.listener.simple.auto-startup=false` this also holds when Spring Test restarts a paused
 * cached context, which starts every listener container regardless of its auto-startup flag.
 */
@Component
@ConditionalOnProperty(AgentStatusConsumer.CONSUMERS_ENABLED, havingValue = "true", matchIfMissing = true)
class AgentStatusConsumer(private val service: AgentRunService) {
    @RabbitListener(queues = [ServerQueues.CODE_REVIEW_STATUS])
    fun on(event: CodeReviewStatusChangedEvent) = service.applyStatus(event)

    companion object {
        const val CONSUMERS_ENABLED = "sift.server.messaging.consumers-enabled"
    }
}
