package org.sift.server.results

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.Finding
import org.sift.events.Severity
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunService
import org.sift.server.agents.RunSource
import org.sift.server.api.NotFoundException
import org.sift.server.api.Page
import org.sift.server.results.persistence.ReviewResultRepository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReviewResultServiceTest {
    private val results = mockk<ReviewResultRepository>(relaxed = true)
    private val runs = mockk<AgentRunService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC)
    private val service = ReviewResultService(results = results, runs = runs, clock = clock)
    private val mapper = JsonMapper.builder().build()

    private val findings = listOf(
        Finding("a.kt", 1, 2, Severity.MAJOR, "style", "rename", null),
        Finding("b.kt", null, null, Severity.INFO, null, "nit", "drop it"),
    )
    private val event = CodeReviewCompletedEvent(
        repositoryUrl = "https://example.org/alpha.git",
        branch = "feature/x",
        baseBranch = "main",
        commitSha = "a".repeat(SHA_LENGTH),
        executionId = "uid-1:1",
        pullRequest = "42",
        summary = "two nits",
        findings = findings,
        completedAt = COMPLETED,
    )

    init {
        every { results.insert(any(), any()) } returns true
    }

    @Test
    fun `store links the run by execution id and completes it with the result`() {
        val run = run(phase = AgentPhase.RUNNING)
        every { runs.findByExecutionId("uid-1:1") } returns run
        val inserted = slot<ReviewResult>()
        every { results.insert(capture(inserted), findings) } returns true

        service.store(event)

        val result = inserted.captured
        assertEquals("uid-1:1", result.executionId)
        assertEquals(run.id, result.agentRunId)
        assertEquals("https://example.org/alpha.git", result.repositoryUrl)
        assertEquals("feature/x", result.branch)
        assertEquals("main", result.baseBranch)
        assertEquals("a".repeat(SHA_LENGTH), result.commitSha)
        assertEquals("42", result.pullRequest)
        assertEquals("two nits", result.summary)
        assertEquals(COMPLETED.atOffset(ZoneOffset.UTC), result.completedAt)
        assertEquals(OffsetDateTime.now(clock), result.receivedAt)

        verify(exactly = 1) { runs.completeWithResult(run.id, COMPLETED.atOffset(ZoneOffset.UTC)) }
    }

    @Test
    fun `store keeps the result unlinked when no run carries the execution id`() {
        every { runs.findByExecutionId("uid-1:1") } returns null
        val inserted = slot<ReviewResult>()
        every { results.insert(capture(inserted), findings) } returns true

        service.store(event)

        assertNull(inserted.captured.agentRunId)
        verify(exactly = 0) { runs.completeWithResult(any(), any()) }
    }

    @Test
    fun `store ignores a duplicate execution id and does not promote the run again`() {
        every { runs.findByExecutionId("uid-1:1") } returns run(phase = AgentPhase.RUNNING)
        every { results.insert(any(), any()) } returns false

        service.store(event)

        verify(exactly = 1) { results.insert(any(), findings) }
        verify(exactly = 0) { runs.completeWithResult(any(), any()) }
    }

    @Test
    fun `get list and findings delegate to the repository and unknown ids are not found`() {
        val result = result()
        every { results.findById(result.id) } returns result
        assertSame(result, service.get(result.id))

        val page = Page(items = listOf(result), page = 0, size = 200, total = 1L)
        every { results.list(ReviewResultFilter(), 0, 200) } returns page
        assertSame(page, service.list(ReviewResultFilter(), page = -3, size = 5000))
        every { results.list(ReviewResultFilter(commitSha = "abc"), 2, 1) } returns page
        assertSame(page, service.list(ReviewResultFilter(commitSha = "abc"), page = 2, size = 0))

        val finding = ReviewFinding(1L, result.id, "a.kt", 1, 2, Severity.MAJOR, "style", "rename", null)
        every { results.findings(result.id, Severity.MAJOR, "a.kt") } returns listOf(finding)
        assertEquals(listOf(finding), service.findings(result.id, Severity.MAJOR, "a.kt"))

        every { results.countFindings(listOf(result.id)) } returns emptyMap()
        assertEquals(mapOf(result.id to 0L), service.findingCounts(listOf(result.id)))

        val missing = UUID.randomUUID()
        every { results.findById(missing) } returns null
        assertFailsWith<NotFoundException> { service.get(missing) }
        assertFailsWith<NotFoundException> { service.findings(missing) }
        verify(exactly = 0) { results.findings(missing, any(), any()) }
    }

    private fun result(): ReviewResult = ReviewResult(
        id = UUID.randomUUID(),
        executionId = "uid-1:1",
        agentRunId = null,
        repositoryUrl = "https://example.org/alpha.git",
        branch = "feature/x",
        baseBranch = "main",
        commitSha = "a".repeat(SHA_LENGTH),
        pullRequest = null,
        summary = "ok",
        completedAt = OffsetDateTime.now(clock),
        receivedAt = OffsetDateTime.now(clock),
    )

    private fun run(phase: AgentPhase): AgentRun {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(clock).minusMinutes(5)
        return AgentRun(
            id = id,
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.API,
            repositoryId = null,
            crName = "cr-$id",
            crUid = "uid-1",
            generation = 1,
            executionId = "uid-1:1",
            phase = phase,
            reason = "JobStarted",
            message = "started",
            spec = mapper.createObjectNode().put("branch", "feature/x"),
            createdAt = now,
            startedAt = now,
            completedAt = null,
            observedAt = now,
            updatedAt = now,
        )
    }

    companion object {
        private const val SHA_LENGTH = 40
        private val COMPLETED: Instant = Instant.parse("2026-09-07T09:59:00Z")
    }
}
