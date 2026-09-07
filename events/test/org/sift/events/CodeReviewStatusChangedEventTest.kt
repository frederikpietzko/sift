package org.sift.events

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeReviewStatusChangedEventTest {

    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    private val event = CodeReviewStatusChangedEvent(
        reviewName = "review-1",
        reviewNamespace = "sift-dev",
        reviewUid = "review-uid",
        generation = 2,
        executionId = "review-uid:2",
        repositoryUrl = "https://github.com/example/sift.git",
        branch = "feature/status-events",
        baseBranch = "main",
        commitSha = "0123456789abcdef0123456789abcdef01234567",
        pullRequest = null,
        phase = "RUNNING",
        reason = "JobRunning",
        message = null,
        startedAt = Instant.parse("2026-09-07T10:00:00Z"),
        completedAt = null,
        observedAt = Instant.parse("2026-09-07T10:00:01Z"),
    )

    @Test
    fun `serializes with stable field names`() {
        val tree = mapper.readTree(mapper.writeValueAsString(event))

        val expectedFields = setOf(
            "reviewName",
            "reviewNamespace",
            "reviewUid",
            "generation",
            "executionId",
            "repositoryUrl",
            "branch",
            "baseBranch",
            "commitSha",
            "pullRequest",
            "phase",
            "reason",
            "message",
            "startedAt",
            "completedAt",
            "observedAt",
        )
        assertEquals(expectedFields, tree.propertyNames().toSet())
        assertEquals("RUNNING", tree.get("phase").asString())
        assertTrue(tree.get("pullRequest").isNull)
        assertTrue(tree.get("completedAt").isNull)
        assertFalse(tree.has("routingKey"))
    }

    @Test
    fun `round trips through json`() {
        val deserialized = mapper.readValue<CodeReviewStatusChangedEvent>(mapper.writeValueAsString(event))

        assertEquals(event, deserialized)
        assertEquals("code-review.status", deserialized.routingKey)
    }
}
