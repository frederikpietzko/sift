package org.sift.server

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.mockk.every
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.sift.crds.CodeReview
import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.events.Finding
import org.sift.events.Severity
import org.sift.messaging.EventPublisher
import org.sift.server.security.TestTokens
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One full round trip over the real wire: REST → CR apply (fabric8 mocked) → operator status event over
 * RabbitMQ → SSE watch → `code-review.completed` event → result query and run promotion. Everything else
 * (Postgres, RabbitMQ, listeners, the `LISTEN/NOTIFY` watch, the bearer-token filter chain) is real; only the
 * JWT decoder is the test one, so requests carry a [TestTokens] bearer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "sift.server.watch.enabled=true",
        "sift.server.watch.heartbeat=1s",
        "sift.server.encryption-key=\${sift.test.encryption-key}",
    ],
)
class ServerEndToEndTest : RabbitMqIntegrationTest() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var publisher: EventPublisher

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val mapper = JsonMapper.builder().build()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val rest: RestClient by lazy {
        RestClient.builder()
            .baseUrl("http://localhost:$port")
            .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
            .build()
    }
    private val repositoryName = "e2e-${UUID.randomUUID()}"

    /** Everything here is committed for real; remove it so the rolled-back repository tests keep their clean slate. */
    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update(
            """
            delete from review_results where agent_run_id in
                (select id from agent_runs where repository_id in (select id from repositories where name = ?))
            """.trimIndent(),
            repositoryName,
        )
        jdbcTemplate.update(
            "delete from agent_runs where repository_id in (select id from repositories where name = ?)",
            repositoryName,
        )
        jdbcTemplate.update("delete from repositories where name = ?", repositoryName)
        jdbcTemplate.update(
            "delete from agent_runs where created_by in (select id from users where issuer = ?)",
            TestTokens.ISSUER,
        )
        jdbcTemplate.update("delete from users where issuer = ?", TestTokens.ISSUER)
    }

    @Test
    fun `a run created via REST is watched, completed by events and queryable as a review result`() {
        val crUid = "uid-e2e-${UUID.randomUUID()}"
        val executionId = "$crUid:1"
        every {
            kubernetesClient.resources(CodeReview::class.java).inNamespace(any()).resource(any()).create()
        } answers {
            CodeReview().apply { metadata = ObjectMetaBuilder().withName("cr-e2e").withUid(crUid).build() }
        }

        val repositoryId = post(
            "/api/v1/repositories",
            """{"name":"$repositoryName","url":"https://example.org/e2e.git"}""",
            HttpStatus.CREATED,
        )["id"].asString()
        val sha = "e".repeat(SHA_LENGTH)
        val run = post(
            "/api/v1/agents",
            """{"kind":"CODE_REVIEW","repositoryId":"$repositoryId","branch":"feature/e2e","baseBranch":"main",
               "commitSha":"$sha","pullRequest":"7"}""",
            HttpStatus.ACCEPTED,
        )
        val runId = run["id"].asString()
        assertEquals(crUid, run["crUid"].asString())
        assertEquals("CREATED", run["phase"].asString())
        assertEquals(TestTokens.USERNAME, run["createdBy"]["username"].asString())
        val creatorId = run["createdBy"]["id"].asString()
        assertEquals(creatorId, get("/api/v1/me")["id"].asString())

        val mine = get("/api/v1/agents?mine=true&repositoryId=$repositoryId")
        assertEquals(listOf(runId), mine["items"].values().map { it["id"].asString() }.toList())
        assertEquals(0L, get("/api/v1/agents?createdBy=${UUID.randomUUID()}")["total"].asLong())

        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents/watch?agentId=$runId"))
            .header("Accept", "text/event-stream")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(HttpStatus.OK.value(), response.statusCode())

        response.body().bufferedReader().use { reader ->
            assertTimeoutPreemptively(STREAM_TIMEOUT) {
                val snapshot = reader.nextFrame()
                assertEquals("SNAPSHOT", snapshot.event)
                assertEquals("CREATED", snapshot.payload()["phase"].asString())

                publisher.publish(statusEvent(crUid, executionId, sha, phase = "RUNNING"))
                val running = generateSequence { reader.nextFrame() }
                    .first { it.event == "UPDATED" && it.payload()["phase"].asString() == "RUNNING" }
                assertEquals(runId, running.payload()["id"].asString())
                assertEquals(executionId, running.payload()["executionId"].asString())

                publisher.publish(completedEvent(executionId, sha))
                val success = generateSequence { reader.nextFrame() }
                    .first { it.event == "UPDATED" && it.payload()["phase"].asString() == "SUCCESS" }
                assertEquals("ResultReceived", success.payload()["reason"].asString())
            }
        }

        val results = await().atMost(Duration.ofSeconds(WAIT_SECONDS))
            .until({ get("/api/v1/results?agentRunId=$runId") }) { it["total"].asLong() == 1L }
        val summary = results["items"][0]
        assertEquals(executionId, summary["executionId"].asString())
        assertEquals(runId, summary["agentRunId"].asString())
        assertEquals(sha, summary["commitSha"].asString())
        assertEquals(2L, summary["findingCount"].asLong())

        val detail = get("/api/v1/results/${summary["id"].asString()}")
        assertEquals("e2e review", detail["summary"].asString())
        assertEquals(listOf("a.kt", "b.kt"), detail["findings"].values().map { it["file"].asString() }.toList())
        val blockers = get("/api/v1/results/${summary["id"].asString()}/findings?severity=BLOCKER")
        assertEquals(1, blockers.size())
        assertEquals("a.kt", blockers[0]["file"].asString())

        val finished = get("/api/v1/agents/$runId")
        assertEquals("SUCCESS", finished["phase"].asString())
        assertEquals("ResultReceived", finished["reason"].asString())
        assertTrue(finished["completedAt"].isString)
    }

    @Test
    fun `the real filter chain rejects anonymous and rejected tokens with 401 problem details`() {
        val anonymous = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HttpStatus.UNAUTHORIZED.value(), anonymous.statusCode())
        assertTrue(anonymous.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse("").startsWith("Bearer"))
        assertTrue(
            anonymous.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("")
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
        )
        assertEquals(HttpStatus.UNAUTHORIZED.value(), mapper.readTree(anonymous.body())["status"].asInt())

        val rejected = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents"))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.authorization(TestTokens.REJECTED))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rejected.statusCode())
        assertTrue(rejected.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse("").contains("invalid_token"))

        val health = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health/readiness")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HttpStatus.OK.value(), health.statusCode())

        val config = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/auth/config")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HttpStatus.OK.value(), config.statusCode())
        assertEquals("sift-web", mapper.readTree(config.body())["clientId"].asString())
    }

    @Test
    fun `authenticated callers are provisioned once and refreshed on every request`() {
        val first = get("/api/v1/me")
        assertEquals(TestTokens.SUBJECT, first["subject"].asString())
        assertEquals(TestTokens.ISSUER, first["issuer"].asString())
        assertEquals(TestTokens.USERNAME, first["username"].asString())
        assertEquals(TestTokens.EMAIL, first["email"].asString())
        val userId = first["id"].asString()

        val renamed = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/me"))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.authorization(TestTokens.bearer(username = "alice.renamed")))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HttpStatus.OK.value(), renamed.statusCode())
        val second = mapper.readTree(renamed.body())
        assertEquals(userId, second["id"].asString())
        assertEquals("alice.renamed", second["username"].asString())

        val rows = jdbcTemplate.queryForList(
            "select username, last_seen_at > created_at as refreshed from users where issuer = ? and subject = ?",
            TestTokens.ISSUER,
            TestTokens.SUBJECT,
        )
        assertEquals(1, rows.size)
        assertEquals("alice.renamed", rows.single()["username"])
        assertEquals(true, rows.single()["refreshed"])
    }

    private fun post(path: String, body: String, expected: HttpStatus): JsonNode {
        val entity = rest.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
            .toEntity(String::class.java)
        assertEquals(expected, entity.statusCode, entity.body)
        return mapper.readTree(assertNotNull(entity.body))
    }

    private fun get(path: String): JsonNode =
        mapper.readTree(assertNotNull(rest.get().uri(path).retrieve().body(String::class.java)))

    private fun statusEvent(
        crUid: String,
        executionId: String,
        sha: String,
        phase: String,
    ): CodeReviewStatusChangedEvent = CodeReviewStatusChangedEvent(
        reviewName = "cr-e2e",
        reviewNamespace = "sift-dev",
        reviewUid = crUid,
        generation = 1,
        executionId = executionId,
        repositoryUrl = "https://example.org/e2e.git",
        branch = "feature/e2e",
        baseBranch = "main",
        commitSha = sha,
        pullRequest = "7",
        phase = phase,
        reason = "JobStarted",
        message = null,
        startedAt = Instant.now(),
        completedAt = null,
        observedAt = Instant.now(),
    )

    private fun completedEvent(executionId: String, sha: String) = CodeReviewCompletedEvent(
        repositoryUrl = "https://example.org/e2e.git",
        branch = "feature/e2e",
        baseBranch = "main",
        commitSha = sha,
        executionId = executionId,
        pullRequest = "7",
        summary = "e2e review",
        findings = listOf(
            Finding("a.kt", 1, 2, Severity.BLOCKER, "bug", "null deref", "check for null"),
            Finding("b.kt", null, null, Severity.INFO, null, "nit", null),
        ),
        completedAt = Instant.now(),
    )

    private data class Frame(val event: String?, val id: String?, val data: String?)

    private fun Frame.payload(): JsonNode = assertNotNull(mapper.readTree(assertNotNull(data))["run"])

    /** Reads until a blank line; comment-only frames (heartbeats) are skipped. */
    private fun BufferedReader.nextFrame(): Frame {
        while (true) {
            var event: String? = null
            var id: String? = null
            var data: String? = null
            var line = readLine() ?: error("stream ended")
            while (line.isNotEmpty()) {
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                    line.startsWith("data:") -> data = (data ?: "") + line.removePrefix("data:").trim()
                }
                line = readLine() ?: error("stream ended")
            }
            if (event != null || id != null || data != null) {
                return Frame(event, id, data)
            }
        }
    }

    companion object {
        private const val SHA_LENGTH = 40
        private const val WAIT_SECONDS = 15L
        private val STREAM_TIMEOUT = Duration.ofSeconds(20)
    }
}
