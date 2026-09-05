package org.sift.agents.bootstrap

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.sift.agents.shared.messaging.EventPublisher
import org.sift.agents.shared.messaging.RabbitEventPublisher
import org.sift.agents.shared.tools.SearxngProperties
import org.sift.agents.shared.tools.SearxngSearchTool
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class SharedAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestApplication::class.java)
        .withPropertyValues(
            "spring.ai.openai.api-key=test-key",
            "spring.rabbitmq.dynamic=false",
        )

    @Test
    fun `shared beans are discovered without component scanning or explicit imports`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(EventPublisher::class.java)
            assertThat(context.getBean(EventPublisher::class.java)).isInstanceOf(RabbitEventPublisher::class.java)
            assertThat(context).hasBean("siftEventsExchange")
            assertThat(context).hasSingleBean(JacksonJsonMessageConverter::class.java)
            assertThat(context.getBean(RabbitTemplate::class.java).messageConverter)
                .isSameAs(context.getBean(MessageConverter::class.java))
            assertThat(context).hasSingleBean(SearxngSearchTool::class.java)
            assertThat(context.getBean(SearxngProperties::class.java).enabled).isTrue()
        }
    }

    @Test
    fun `web search can be disabled without disabling messaging`() {
        runner.withPropertyValues("sift.tools.web-search.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(SearxngSearchTool::class.java)
            assertThat(context).hasSingleBean(EventPublisher::class.java)
        }
    }

    @Test
    fun `application beans override shared defaults`() {
        val publisher = mockk<EventPublisher>()
        val converter = mockk<MessageConverter>()
        val searchTool = mockk<SearxngSearchTool>()
        val exchange = TopicExchange("custom.events")
        runner
            .withBean(EventPublisher::class.java, { publisher })
            .withBean(MessageConverter::class.java, { converter })
            .withBean(SearxngSearchTool::class.java, { searchTool })
            .withBean("siftEventsExchange", TopicExchange::class.java, { exchange })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EventPublisher::class.java)
                assertThat(context.getBean(EventPublisher::class.java)).isSameAs(publisher)
                assertThat(context).hasSingleBean(MessageConverter::class.java)
                assertThat(context.getBean(RabbitTemplate::class.java).messageConverter).isSameAs(converter)
                assertThat(context).hasSingleBean(SearxngSearchTool::class.java)
                assertThat(context.getBean(SearxngSearchTool::class.java)).isSameAs(searchTool)
                assertThat(context.getBean("siftEventsExchange")).isSameAs(exchange)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestApplication
}