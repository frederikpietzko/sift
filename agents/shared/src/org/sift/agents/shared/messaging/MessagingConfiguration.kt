package org.sift.agents.shared.messaging

import org.springframework.amqp.core.ExchangeBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Wires up the RabbitMQ messaging infrastructure for publishing [org.sift.events.SiftEvent]s.
 *
 * The [JacksonJsonMessageConverter] bean is picked up by Spring Boot's RabbitMQ auto-configuration
 * and applied to the auto-configured [RabbitTemplate]. Connection resilience is not configured
 * here: consuming applications are expected to set `spring.rabbitmq.template.retry` and
 * `spring.rabbitmq.connection-timeout` in their `application.yaml`.
 */
@AutoConfiguration(before = [RabbitAutoConfiguration::class])
class MessagingConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["siftEventsExchange"])
    fun siftEventsExchange(): TopicExchange =
        ExchangeBuilder.topicExchange(RabbitEventPublisher.EXCHANGE)
            .durable(true)
            .build()

    @Bean
    @ConditionalOnMissingBean(MessageConverter::class)
    fun messageConverter(): JacksonJsonMessageConverter =
        JacksonJsonMessageConverter(
            JsonMapper.builder()
                .addModule(kotlinModule())
                .build(),
        )

    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun eventPublisher(rabbitTemplate: RabbitTemplate): RabbitEventPublisher =
        RabbitEventPublisher(rabbitTemplate)
}
