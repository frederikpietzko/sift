package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import org.sift.e2e.SseFrameReader.Companion.payload
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The one happy path, driven purely through the server's public API against the real stack booted by
 * [SiftEnvironment]: repository → `CODE_REVIEW` run → SSE watch to `SUCCESS` → result via REST.
 * Assertions are limited to stable contracts (status codes, phase transitions, presence/shape of the
 * result); nothing about the LLM output, timings or intermediate resource names is checked.
 */
@EnabledIfEnvironmentVariable(named = "SIFT_E2E", matches = "true")
@ExtendWith(SiftEnvironment::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeReviewHappyPathTest(private val env: SiftEnvironment) {
    private val api by lazy { ServerApi(env.serverBaseUrl) }
    private val repositoryName = "e2e-${UUID.randomUUID()}"

    @Test
    fun `a code review requested through the API runs on the cluster and yields a result`() {
        val spec = SamplePullRequest.resolve()
        var crName: String? = null
        var repositoryId: String? = null
        try {
            repositoryId = createRepository(spec)
            val run = createRun(repositoryId, spec)
            crName = run.path("crName").asString()
            val runId = run.path("id").asString()
            val crUid = run.path("crUid").asString()

            val outcome = watchUntilSuccess(runId)
            assertTrue(RunObserver.RUNNING in outcome.phases, "RUNNING must precede SUCCESS; observed ${outcome.phases}")
            assertEquals(RunObserver.SUCCESS, outcome.phases.last())
            assertEquals(1, outcome.phases.count { it in RunObserver.terminalPhases }, "terminal phase must be final")

            verifyRun(runId, crUid)
            verifyResult(runId, spec)
        } finally {
            cleanUp(crName, repositoryId)
        }
    }

    private fun createRepository(spec: ReviewSpec): String {
        val response = api.post("/api/v1/repositories", """{"name":"$repositoryName","url":"${spec.repositoryUrl}"}""")
        assertEquals(ServerApi.HTTP_CREATED, response.status, response.rawBody)
        return response.json().path("id").asString()
    }

    private fun createRun(repositoryId: String, spec: ReviewSpec): JsonNode {
        val body = """
            {"kind":"CODE_REVIEW","repositoryId":"$repositoryId","branch":"${spec.branch}",
             "baseBranch":"${spec.baseBranch}","commitSha":"${spec.commitSha}","pullRequest":"${spec.pullRequest}"}
        """.trimIndent()
        val response = api.post("/api/v1/agents", body)
        assertEquals(ServerApi.HTTP_ACCEPTED, response.status, response.rawBody)
        val run = response.json()
        assertEquals("CREATED", run.path("phase").asString())
        assertFalse(run.path("crUid").isNull || run.path("crUid").asString().isBlank(), "crUid must be set")
        println("Created run ${run.path("id").asString()} (CR ${run.path("crName").asString()})")
        return run
    }

    private fun watchUntilSuccess(runId: String): RunOutcome =
        api.watch(runId).use { stream ->
            assertEquals(ServerApi.HTTP_OK, stream.status)
            assertTimeoutPreemptively(scenarioBudget.toJavaDuration()) {
                val snapshot = stream.reader.nextFrame()
                assertEquals("SNAPSHOT", snapshot.event)
                assertEquals(runId, snapshot.payload().path("id").asString())
                RunObserver.followUntilTerminal(stream.reader.frames()).also { println("Phases: ${it.phases}") }
            }
        }

    private fun verifyRun(runId: String, crUid: String) {
        val run = api.getJson("/api/v1/agents/$runId")
        assertEquals(RunObserver.SUCCESS, run.path("phase").asString())
        assertTrue(run.path("completedAt").isString, "completedAt must be present")
        assertEquals("$crUid:1", run.path("executionId").asString())
    }

    private fun verifyResult(runId: String, spec: ReviewSpec) {
        val page = api.getJson("/api/v1/results?agentRunId=$runId")
        assertEquals(1L, page.path("total").asLong(), page.toString())
        val summary = page.path("items")[0]
        assertEquals(spec.commitSha, summary.path("commitSha").asString())

        val detail = api.getJson("/api/v1/results/${summary.path("id").asString()}")
        assertTrue(detail.path("summary").isString && detail.path("summary").asString().isNotBlank())
        assertTrue(detail.path("findings").isArray, "findings must be a JSON array")
        assertEquals(detail.path("findings").size().toLong(), detail.path("findingCount").asLong())
    }

    private fun cleanUp(crName: String?, repositoryId: String?) {
        crName?.takeIf { it.isNotBlank() }?.let { TestData.deleteCodeReview(env.kubernetesClient, it) }
        repositoryId?.let { TestData.deleteRepositorySecret(env.kubernetesClient, it) }
        val purged = TestData.purgeRows(repositoryName, jdbcUrl = env.jdbcUrl)
        println("Cleaned up $repositoryName ($purged rows)")
    }

    companion object {
        private const val TIMEOUT_VARIABLE = "SIFT_E2E_TIMEOUT"

        /** Whole scenario budget, e.g. `SIFT_E2E_TIMEOUT=30m`; the first run also pulls the review image. */
        private val scenarioBudget: Duration =
            System.getenv(TIMEOUT_VARIABLE)?.takeIf { it.isNotBlank() }?.let(Duration::parse) ?: 20.minutes
    }
}
