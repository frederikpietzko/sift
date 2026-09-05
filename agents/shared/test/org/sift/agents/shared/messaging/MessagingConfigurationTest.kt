package org.sift.agents.shared.messaging

import org.junit.jupiter.api.Test
import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.Finding
import org.sift.events.Severity
import org.springframework.amqp.core.MessageProperties
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingConfigurationTest {
    private val configuration = MessagingConfiguration()

    @Test
    fun `sift events exchange is a durable non auto-delete topic exchange`() {
        val exchange = configuration.siftEventsExchange()

        assertEquals("sift.events", exchange.name)
        assertTrue(exchange.isDurable)
        assertFalse(exchange.isAutoDelete)
    }

    @Test
    fun `message converter serializes a code review completed event to json`() {
        val converter = configuration.messageConverter()
        val event = CodeReviewCompletedEvent(
            repositoryUrl = "https://example.com/org/repo.git",
            branch = "feature/messaging",
            baseBranch = "main",
            pullRequest = "42",
            summary = "Looks good overall.",
            findings = listOf(
                Finding(
                    file = "src/Main.kt",
                    startLine = 10,
                    endLine = 12,
                    severity = Severity.MAJOR,
                    category = "correctness",
                    message = "Possible NPE.",
                    suggestion = "Use a safe call.",
                ),
            ),
            completedAt = Instant.parse("2026-09-05T00:00:00Z"),
        )

        val message = converter.toMessage(event, MessageProperties())

        assertEquals("application/json", message.messageProperties.contentType)
        val body = message.body.decodeToString()
        assertTrue(body.contains(""""repositoryUrl":"https://example.com/org/repo.git""""))
        assertTrue(body.contains(""""branch":"feature/messaging""""))
        assertTrue(body.contains(""""baseBranch":"main""""))
        assertTrue(body.contains(""""pullRequest":"42""""))
        assertTrue(body.contains(""""summary":"Looks good overall.""""))
        assertTrue(body.contains(""""severity":"MAJOR""""))
        assertTrue(body.contains(""""message":"Possible NPE.""""))
        assertFalse(body.contains("routingKey"))
    }
}
