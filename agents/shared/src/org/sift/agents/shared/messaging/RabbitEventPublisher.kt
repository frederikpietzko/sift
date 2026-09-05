package org.sift.agents.shared.messaging

import org.sift.events.SiftEvent
import org.springframework.amqp.rabbit.core.RabbitTemplate

/**
 * [EventPublisher] backed by RabbitMQ, publishing events to the [EXCHANGE] topic exchange.
 *
 * Publishing does not implement its own retry logic. Consuming applications are expected to
 * configure `spring.rabbitmq.template.retry` and `spring.rabbitmq.connection-timeout` in their
 * `application.yaml` so that transient broker outages are retried and connection attempts fail
 * fast instead of blocking indefinitely.
 */
class RabbitEventPublisher(
    private val rabbitTemplate: RabbitTemplate,
) : EventPublisher {
    override fun publish(event: SiftEvent) {
        rabbitTemplate.convertAndSend(EXCHANGE, event.routingKey, event)
    }

    companion object {
        /**
         * Name of the topic exchange all sift events are published to.
         */
        const val EXCHANGE: String = "sift.events"
    }
}
