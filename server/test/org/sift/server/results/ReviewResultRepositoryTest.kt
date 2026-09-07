package org.sift.server.results

import org.sift.events.Finding
import org.sift.events.Severity
import org.sift.server.PostgresIntegrationTest
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunRepository
import org.sift.server.agents.RunSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each test runs inside a Spring transaction that is rolled back, so the shared database stays clean. */
@Transactional
class ReviewResultRepositoryTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var results: ReviewResultRepository

    @Autowired
    private lateinit var runs: AgentRunRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val mapper = JsonMapper.builder().build()
    private val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    @Test
    fun `insert stores the result with its findings and round trips all columns`() {
        val run = run()
        val result = result(executionId = "exec-1", agentRunId = run.id, completedAt = now.minusMinutes(1))
            .copy(pullRequest = "42")

        assertTrue(results.insert(result, listOf(finding("a.kt", Severity.MAJOR), finding("b.kt", Severity.INFO))))

        val found = assertNotNull(results.findById(result.id))
        assertEquals(result.id, found.id)
        assertEquals("exec-1", found.executionId)
        assertEquals(run.id, found.agentRunId)
        assertEquals("https://example.org/alpha.git", found.repositoryUrl)
        assertEquals("feature/x", found.branch)
        assertEquals("main", found.baseBranch)
        assertEquals("a".repeat(SHA_LENGTH), found.commitSha)
        assertEquals("42", found.pullRequest)
        assertEquals("looks good", found.summary)
        assertTrue(now.minusMinutes(1).isEqual(found.completedAt))
        assertTrue(now.isEqual(found.receivedAt))
        assertEquals(found, results.findByExecutionId("exec-1"))
        assertNull(results.findByExecutionId("missing"))
        assertNull(results.findById(UUID.randomUUID()))

        val findings = results.findingsByResultId(result.id)
        assertEquals(listOf("a.kt", "b.kt"), findings.map { it.file })
        val first = findings.first()
        assertEquals(result.id, first.resultId)
        assertEquals(Severity.MAJOR, first.severity)
        assertEquals(1, first.startLine)
        assertEquals(3, first.endLine)
        assertEquals("style", first.category)
        assertEquals("message for a.kt", first.message)
        assertEquals("fix it", first.suggestion)
        assertTrue(first.id > 0)
    }

    @Test
    fun `a duplicate execution id is ignored without duplicating findings`() {
        val original = result(executionId = "exec-dup", agentRunId = null, completedAt = now)
        assertTrue(results.insert(original, listOf(finding("a.kt", Severity.MINOR))))

        val duplicate = result(executionId = "exec-dup", agentRunId = null, completedAt = now.plusMinutes(1))
            .copy(summary = "second attempt")
        assertFalse(results.insert(duplicate, listOf(finding("x.kt", Severity.BLOCKER), finding("y.kt", Severity.INFO))))

        val stored = assertNotNull(results.findByExecutionId("exec-dup"))
        assertEquals(original.id, stored.id)
        assertEquals("looks good", stored.summary)
        assertNull(results.findById(duplicate.id))
        assertEquals(listOf("a.kt"), results.findingsByResultId(original.id).map { it.file })
        assertTrue(results.findingsByResultId(duplicate.id).isEmpty())
    }

    @Test
    fun `list filters by repository commit and run sorted by completion with pagination and counts`() {
        jdbcTemplate.update("delete from review_results")
        val run = run()
        val oldest = result("e-1", null, now.minusHours(3), url = "https://example.org/alpha.git", sha = "a")
        val middle = result("e-2", run.id, now.minusHours(2), url = "https://example.org/beta.git", sha = "b")
        val newer = result("e-3", null, now.minusHours(1), url = "https://example.org/alpha.git", sha = "b")
        val newest = result("e-4", run.id, now, url = "https://example.org/alpha.git", sha = "a")
        results.insert(oldest, listOf(finding("a.kt", Severity.INFO), finding("b.kt", Severity.INFO)))
        results.insert(middle, emptyList())
        results.insert(newer, listOf(finding("c.kt", Severity.MAJOR)))
        results.insert(newest, emptyList())

        val all = results.list(ReviewResultFilter(), page = 0, size = 10)
        assertEquals(listOf(newest.id, newer.id, middle.id, oldest.id), all.items.map { it.id })
        assertEquals(4L, all.total)
        assertEquals(0, all.page)
        assertEquals(10, all.size)

        val pageOne = results.list(ReviewResultFilter(), page = 1, size = 2)
        assertEquals(listOf(middle.id, oldest.id), pageOne.items.map { it.id })
        assertEquals(4L, pageOne.total)
        assertTrue(results.list(ReviewResultFilter(), page = 2, size = 2).items.isEmpty())

        val alpha = results.list(ReviewResultFilter(repositoryUrl = "https://example.org/alpha.git"), 0, 10)
        assertEquals(listOf(newest.id, newer.id, oldest.id), alpha.items.map { it.id })
        assertEquals(3L, alpha.total)

        val shaB = results.list(ReviewResultFilter(commitSha = "b".repeat(SHA_LENGTH)), 0, 10)
        assertEquals(listOf(newer.id, middle.id), shaB.items.map { it.id })

        val byRun = results.list(ReviewResultFilter(agentRunId = run.id), 0, 10)
        assertEquals(listOf(newest.id, middle.id), byRun.items.map { it.id })

        val combined = results.list(
            ReviewResultFilter(repositoryUrl = "https://example.org/alpha.git", commitSha = "a".repeat(SHA_LENGTH)),
            page = 0,
            size = 10,
        )
        assertEquals(listOf(newest.id, oldest.id), combined.items.map { it.id })
        assertEquals(2L, combined.total)

        assertEquals(
            mapOf(oldest.id to 2L, newer.id to 1L),
            results.countFindings(listOf(oldest.id, middle.id, newer.id, UUID.randomUUID())),
        )
        assertTrue(results.countFindings(emptyList()).isEmpty())

        assertFailsWith<IllegalArgumentException> { results.list(ReviewResultFilter(), page = -1, size = 10) }
        assertFailsWith<IllegalArgumentException> { results.list(ReviewResultFilter(), page = 0, size = 0) }
    }

    @Test
    fun `findings filter by severity and exact file`() {
        val result = result(executionId = "exec-f", agentRunId = null, completedAt = now)
        results.insert(
            result,
            listOf(
                finding("src/a.kt", Severity.MAJOR),
                finding("src/a.kt", Severity.INFO),
                finding("src/b.kt", Severity.MAJOR),
                finding("src/a.kt", Severity.BLOCKER),
            ),
        )

        assertEquals(4, results.findings(result.id, severity = null, file = null).size)
        assertEquals(
            listOf("src/a.kt", "src/b.kt"),
            results.findings(result.id, severity = Severity.MAJOR, file = null).map { it.file },
        )
        assertEquals(
            listOf(Severity.MAJOR, Severity.INFO, Severity.BLOCKER),
            results.findings(result.id, severity = null, file = "src/a.kt").map { it.severity },
        )
        assertEquals(1, results.findings(result.id, severity = Severity.MAJOR, file = "src/a.kt").size)
        assertTrue(results.findings(result.id, severity = null, file = "src").isEmpty())
        assertTrue(results.findings(result.id, severity = Severity.MINOR, file = null).isEmpty())
        assertTrue(results.findings(UUID.randomUUID(), severity = null, file = null).isEmpty())
    }

    private fun result(
        executionId: String,
        agentRunId: UUID?,
        completedAt: OffsetDateTime,
        url: String = "https://example.org/alpha.git",
        sha: String = "a",
    ): ReviewResult = ReviewResult(
        id = UUID.randomUUID(),
        executionId = executionId,
        agentRunId = agentRunId,
        repositoryUrl = url,
        branch = "feature/x",
        baseBranch = "main",
        commitSha = sha.repeat(SHA_LENGTH),
        pullRequest = null,
        summary = "looks good",
        completedAt = completedAt,
        receivedAt = now,
    )

    private fun finding(file: String, severity: Severity): Finding = Finding(
        file = file,
        startLine = 1,
        endLine = 3,
        severity = severity,
        category = "style",
        message = "message for $file",
        suggestion = "fix it",
    )

    private fun run(): AgentRun {
        val id = UUID.randomUUID()
        return runs.insert(
            AgentRun(
                id = id,
                kind = AgentKind.CODE_REVIEW,
                source = RunSource.API,
                repositoryId = null,
                crName = "cr-$id",
                crUid = null,
                generation = null,
                executionId = null,
                phase = AgentPhase.RUNNING,
                reason = null,
                message = null,
                spec = mapper.createObjectNode().put("branch", "feature/x"),
                createdAt = now,
                startedAt = null,
                completedAt = null,
                observedAt = null,
                updatedAt = now,
            ),
        )
    }

    companion object {
        private const val SHA_LENGTH = 40
    }
}
