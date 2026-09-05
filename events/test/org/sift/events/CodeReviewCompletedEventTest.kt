package org.sift.events

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeReviewCompletedEventTest {

    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    private val event = CodeReviewCompletedEvent(
        repositoryUrl = "https://github.com/example/sift.git",
        branch = "feature/events-module",
        baseBranch = "main",
        pullRequest = null,
        summary = "Looks good overall, one blocker found.",
        findings = [
            Finding(
                file = "src/org/sift/events/SiftEvent.kt",
                startLine = 10,
                endLine = 12,
                severity = Severity.BLOCKER,
                category = "correctness",
                message = "Potential NPE when routing key is missing.",
                suggestion = "Validate the routing key before publishing.",
            ),
            Finding(
                file = "README.md",
                startLine = null,
                endLine = null,
                severity = Severity.INFO,
                category = null,
                message = "Consider documenting the new module.",
                suggestion = null,
            ),
        ],
        completedAt = Instant.parse("2026-09-05T00:00:00Z"),
    )

    @Test
    fun `serializes with stable field names`() {
        val json = mapper.writeValueAsString(event)
        val tree = mapper.readTree(json)

        val expectedFields = setOf(
            "repositoryUrl",
            "branch",
            "baseBranch",
            "pullRequest",
            "summary",
            "findings",
            "completedAt",
        )
        assertEquals(expectedFields, tree.propertyNames().toSet())

        val finding = tree.get("findings").get(0)
        val expectedFindingFields = setOf(
            "file",
            "startLine",
            "endLine",
            "severity",
            "category",
            "message",
            "suggestion",
        )
        assertEquals(expectedFindingFields, finding.propertyNames().toSet())
        assertEquals("BLOCKER", finding.get("severity").asString())
    }

    @Test
    fun `serializes nulls explicitly and ignores routing key`() {
        val json = mapper.writeValueAsString(event)
        val tree = mapper.readTree(json)

        assertTrue(tree.get("pullRequest").isNull)
        assertFalse(tree.has("routingKey"))

        val infoFinding = tree.get("findings").get(1)
        assertTrue(infoFinding.get("startLine").isNull)
        assertTrue(infoFinding.get("endLine").isNull)
        assertTrue(infoFinding.get("category").isNull)
        assertTrue(infoFinding.get("suggestion").isNull)
    }

    @Test
    fun `round trips through json`() {
        val json = mapper.writeValueAsString(event)
        val deserialized = mapper.readValue<CodeReviewCompletedEvent>(json)

        assertEquals(event, deserialized)
        assertEquals("code-review.completed", deserialized.routingKey)
    }
}
