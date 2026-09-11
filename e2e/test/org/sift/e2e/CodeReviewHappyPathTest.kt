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
 * [SiftEnvironment]: repository → `CODE_REVIEW` run → SSE watch to `SUCCESS` → result via REST →
 * revise into a successor run that reaches `SUCCESS` while the predecessor's result stays readable, all
 * authenticated as the Keycloak `e2e` user. Assertions are limited to stable contracts (status codes,
 * phase transitions, run attribution, presence/shape of the result); nothing about the LLM output,
 * timings or intermediate resource names is checked.
 */
@EnabledIfEnvironmentVariable(named = "SIFT_E2E", matches = "true")
@ExtendWith(SiftEnvironment::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeReviewHappyPathTest(private val env: SiftEnvironment) {
    private val api by lazy { env.api() }
    private val repositoryName = "e2e-${UUID.randomUUID()}"

    @Test
    fun `a code review requested through the API runs on the cluster and yields a result`() {
        val spec = SamplePullRequest.resolve()
        var crName: String? = null
        var successorCrName: String? = null
        var repositoryId: String? = null
        try {
            repositoryId = createRepository(spec)
            val run = createRun(repositoryId, spec)
            crName = run.path("crName").asString()
            val runId = run.path("id").asString()
            val crUid = run.path("crUid").asString()
            verifyAttribution(runId, repositoryId)

            val outcome = watchUntilSuccess(runId)
            assertTrue(RunObserver.RUNNING in outcome.phases, "RUNNING must precede SUCCESS; observed ${outcome.phases}")
            assertEquals(RunObserver.SUCCESS, outcome.phases.last())
            assertEquals(1, outcome.phases.count { it in RunObserver.terminalPhases }, "terminal phase must be final")

            verifyRun(runId, crUid)
            val resultId = verifyResult(runId, spec)

            val successor = revise(runId, repositoryId, spec)
            successorCrName = successor.path("crName").asString()
            val successorId = successor.path("id").asString()
            assertEquals(runId, successor.path("supersedesRunId").asString(), successor.toString())

            val successorOutcome = watchUntilSuccess(successorId)
            assertEquals(RunObserver.SUCCESS, successorOutcome.phases.last())

            verifyLineage(predecessorId = runId, successorId = successorId)
            verifyRetainedResult(runId, resultId, spec)
            verifyResult(successorId, spec)
        } finally {
            cleanUp(listOfNotNull(crName, successorCrName), repositoryId)
        }
    }

    /** `PUT /api/v1/agents/{id}` starts the edited spec as a new run instead of mutating the finished one. */
    private fun revise(runId: String, repositoryId: String, spec: ReviewSpec): JsonNode {
        val body = """
            {"repositoryId":"$repositoryId","branch":"${spec.branch}",
             "baseBranch":"${spec.baseBranch}","commitSha":"${spec.commitSha}","pullRequest":"${spec.pullRequest}"}
        """.trimIndent()
        val response = api.put("/api/v1/agents/$runId", body)
        assertEquals(ServerApi.HTTP_CREATED, response.status, response.rawBody)
        val successor = response.json()
        assertEquals("CREATED", successor.path("phase").asString())
        assertFalse(successor.path("id").asString() == runId, "revision must create a new run")
        println("Revised run $runId as ${successor.path("id").asString()}")
        return successor
    }

    /** Both directions of the chain are readable: the predecessor points forward, the successor backward. */
    private fun verifyLineage(predecessorId: String, successorId: String) {
        val predecessor = api.getJson("/api/v1/agents/$predecessorId")
        assertEquals(successorId, predecessor.path("supersededByRunId").asString(), predecessor.toString())
        val successor = api.getJson("/api/v1/agents/$successorId")
        assertEquals(predecessorId, successor.path("supersedesRunId").asString(), successor.toString())
    }

    /** The predecessor's stored result survives the revision even though its `CodeReview` is gone. */
    private fun verifyRetainedResult(runId: String, resultId: String, spec: ReviewSpec) {
        val page = api.getJson("/api/v1/results?agentRunId=$runId")
        assertEquals(1L, page.path("total").asLong(), page.toString())
        assertEquals(resultId, page.path("items")[0].path("id").asString(), page.toString())
        val detail = api.getJson("/api/v1/results/$resultId")
        assertEquals(spec.commitSha, detail.path("commitSha").asString(), detail.toString())
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

    private fun verifyAttribution(runId: String, repositoryId: String) {
        val me = api.getJson("/api/v1/me")
        assertEquals(Compose.E2E_USER, me.path("username").asString())
        val run = api.getJson("/api/v1/agents/$runId")
        assertEquals(Compose.E2E_USER, run.path("createdBy").path("username").asString(), run.toString())
        assertEquals(me.path("id").asString(), run.path("createdBy").path("id").asString(), run.toString())

        val mine = api.getJson("/api/v1/agents?mine=true&repositoryId=$repositoryId")
        assertTrue(mine.path("items").any { it.path("id").asString() == runId }, "?mine=true must list $runId: $mine")
    }

    private fun verifyRun(runId: String, crUid: String) {
        val run = api.getJson("/api/v1/agents/$runId")
        assertEquals(RunObserver.SUCCESS, run.path("phase").asString())
        assertTrue(run.path("completedAt").isString, "completedAt must be present")
        assertEquals("$crUid:1", run.path("executionId").asString())
        assertEquals(Compose.E2E_USER, run.path("createdBy").path("username").asString())
    }

    /** Returns the id of the ingested result so later assertions can prove it is the very same row. */
    private fun verifyResult(runId: String, spec: ReviewSpec): String {
        val page = api.getJson("/api/v1/results?agentRunId=$runId")
        assertEquals(1L, page.path("total").asLong(), page.toString())
        val summary = page.path("items")[0]
        assertEquals(spec.commitSha, summary.path("commitSha").asString())

        val detail = api.getJson("/api/v1/results/${summary.path("id").asString()}")
        assertTrue(detail.path("summary").isString && detail.path("summary").asString().isNotBlank())
        assertTrue(detail.path("findings").isArray, "findings must be a JSON array")
        assertEquals(detail.path("findings").size().toLong(), detail.path("findingCount").asLong())
        return summary.path("id").asString()
    }

    private fun cleanUp(crNames: List<String>, repositoryId: String?) {
        crNames.filter { it.isNotBlank() }.forEach { TestData.deleteCodeReview(env.kubernetesClient, it) }
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
