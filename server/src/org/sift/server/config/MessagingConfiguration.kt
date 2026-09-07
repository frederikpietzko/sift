package org.sift.server.config

import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.CodeReviewStatusChangedEvent
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.ExchangeBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Names of the server-owned RabbitMQ queues and exchanges. */
object ServerQueues {
    const val DEAD_LETTER_EXCHANGE = "sift.events.dlx"
    const val DEAD_LETTER = "sift.server.dead-letter"
    const val CODE_REVIEW_STATUS = "sift.server.code-review.status"
    const val CODE_REVIEW_COMPLETED = "sift.server.code-review.completed"
}

/**
 * Declares the server's consumer topology on the shared `sift.events` exchange (bean `siftEventsExchange`
 * from `//messaging`). Each queue dead-letters rejected messages to `sift.events.dlx`, which fans everything
 * (`#`) into `sift.server.dead-letter`; together with `default-requeue-rejected=false` a message whose
 * listener throws is parked there instead of being redelivered in a loop.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {
    @Bean
    fun serverQueues(siftEventsExchange: TopicExchange): Declarables {
        val deadLetterExchange = ExchangeBuilder.topicExchange(ServerQueues.DEAD_LETTER_EXCHANGE)
            .durable(true)
            .build<TopicExchange>()
        val deadLetterQueue = QueueBuilder.durable(ServerQueues.DEAD_LETTER).build()
        val statusQueue = consumerQueue(ServerQueues.CODE_REVIEW_STATUS)
        val completedQueue = consumerQueue(ServerQueues.CODE_REVIEW_COMPLETED)
        return Declarables(
            deadLetterExchange,
            deadLetterQueue,
            BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#"),
            statusQueue,
            BindingBuilder.bind(statusQueue).to(siftEventsExchange).with(CodeReviewStatusChangedEvent.ROUTING_KEY),
            completedQueue,
            BindingBuilder.bind(completedQueue).to(siftEventsExchange).with(CodeReviewCompletedEvent.ROUTING_KEY),
        )
    }

    private fun consumerQueue(name: String): Queue =
        QueueBuilder.durable(name).deadLetterExchange(ServerQueues.DEAD_LETTER_EXCHANGE).build()
}
