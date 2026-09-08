package org.sift.server.agents

import com.ninjasquad.springmockk.MockkBean
import io.fabric8.kubernetes.client.KubernetesClientException
import io.mockk.every
import io.mockk.verify
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.server.api.ApiExceptionHandler
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.sift.server.security.SecurityConfiguration
import org.sift.server.security.TestSecurityConfiguration
import org.sift.server.security.TestTokens
import org.sift.server.users.TestUsers
import org.sift.server.users.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * `Application` imports [ExposedAutoConfiguration] explicitly; the MVC slice has no data source, so it is excluded.
 * The real security chain is imported and every request is authenticated with a mock JWT.
 */
@WebMvcTest(controllers = [AgentRunController::class], excludeAutoConfiguration = [ExposedAutoConfiguration::class])
@Import(ApiExceptionHandler::class, SecurityConfiguration::class, TestSecurityConfiguration::class)
class AgentRunControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: AgentRunService

    @MockkBean
    private lateinit var users: UserService

    private val user = TestTokens.authenticated()

    @BeforeTest
    fun stubProvisioning() = TestUsers.stubProvisioning(users)

    private val id: UUID = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5")
    private val repositoryId: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val sha = "a".repeat(SHA_LENGTH)
    private val run = AgentRun(
        id = id,
        kind = AgentKind.CODE_REVIEW,
        source = RunSource.API,
        repositoryId = repositoryId,
        crName = "cr-$id",
        crUid = "uid-1",
        generation = 1,
        executionId = "uid-1:1",
        phase = AgentPhase.CREATED,
        reason = null,
        message = null,
        spec = JsonMapper.builder().build().createObjectNode().put("branch", "feature/x").put("commitSha", sha),
        createdAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
        startedAt = null,
        completedAt = null,
        observedAt = OffsetDateTime.parse("2026-09-07T10:00:30Z"),
        updatedAt = OffsetDateTime.parse("2026-09-07T10:01:00Z"),
        createdBy = RunCreator(id = TestUsers.ALICE_ID, username = TestUsers.alice.username),
    )
    private val body = """
        {"kind":"CODE_REVIEW","repositoryId":"$repositoryId","branch":"feature/x","baseBranch":"main",
         "commitSha":"$sha","pullRequest":"42"}
    """.trimIndent()

    @Test
    fun `POST creates a run and returns 202 with location`() {
        val expected = CreateAgentRunRequest(
            kind = AgentKind.CODE_REVIEW,
            repositoryId = repositoryId,
            branch = "feature/x",
            baseBranch = "main",
            commitSha = sha,
            pullRequest = "42",
        )
        every { service.create(expected, TestUsers.alice) } returns run

        mockMvc.post("/api/v1/agents") {
            with(user)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isAccepted() }
            header { string("Location", "http://localhost/api/v1/agents/$id") }
            jsonPath("$.id") { value(id.toString()) }
            jsonPath("$.kind") { value("CODE_REVIEW") }
            jsonPath("$.source") { value("API") }
            jsonPath("$.repositoryId") { value(repositoryId.toString()) }
            jsonPath("$.crName") { value("cr-$id") }
            jsonPath("$.crUid") { value("uid-1") }
            jsonPath("$.phase") { value("CREATED") }
            jsonPath("$.spec.branch") { value("feature/x") }
            jsonPath("$.spec.commitSha") { value(sha) }
            jsonPath("$.createdAt") { exists() }
            jsonPath("$.updatedAt") { exists() }
            jsonPath("$.observedAt") { doesNotExist() }
            jsonPath("$.createdBy.id") { value(TestUsers.ALICE_ID.toString()) }
            jsonPath("$.createdBy.username") { value(TestUsers.alice.username) }
        }
    }

    @Test
    fun `POST with invalid body yields problem detail 400 without calling the service`() {
        val valid = mapOf(
            "kind" to "\"CODE_REVIEW\"",
            "repositoryId" to "\"$repositoryId\"",
            "branch" to "\"x\"",
            "baseBranch" to "\"main\"",
            "commitSha" to "\"$sha\"",
        )
        fun json(vararg overrides: Pair<String, String?>): String = (valid + overrides)
            .filterValues { it != null }
            .entries.joinToString(",", "{", "}") { (key, value) -> "\"$key\":$value" }

        listOf(
            json("branch" to "\"\""),
            json("baseBranch" to "\" \""),
            json("commitSha" to "\"abc\""),
            json("kind" to "\"SECURITY_REVIEW\""),
            json("repositoryId" to "\"nope\""),
            json("repositoryId" to null),
            json("branch" to null),
            """{not json""",
        ).forEach { invalid ->
            mockMvc.post("/api/v1/agents") {
                with(user)
                contentType = MediaType.APPLICATION_JSON
                content = invalid
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(400) }
            }
        }
        verify(exactly = 0) { service.create(any(), any()) }
    }

    @Test
    fun `domain exceptions map to 404 409 and 500 problem details`() {
        every { service.get(id) } throws NotFoundException("Agent run $id not found")
        mockMvc.get("/api/v1/agents/$id") { with(user) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Agent run $id not found") }
        }

        every { service.cancel(id) } throws ConflictException("already SUCCESS")
        mockMvc.post("/api/v1/agents/$id/cancel") { with(user) }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("already SUCCESS") }
        }

        every { service.create(any(), any()) } throws KubernetesClientException("forbidden")
        mockMvc.post("/api/v1/agents") {
            with(user)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.detail") { value("An unexpected error occurred") }
        }
    }

    @Test
    fun `GET list passes filters and paging and GET by id returns the run`() {
        val filter = AgentRunFilter(kind = AgentKind.CODE_REVIEW, phase = AgentPhase.RUNNING, repositoryId = repositoryId)
        every { service.list(filter, 2, 5) } returns Page(items = listOf(run), page = 2, size = 5, total = 11)
        every { service.list(AgentRunFilter(), 0, 20) } returns Page(items = emptyList(), page = 0, size = 20, total = 0)
        every { service.get(id) } returns run

        val query = "kind=CODE_REVIEW&phase=RUNNING&repositoryId=$repositoryId&page=2&size=5"
        mockMvc.get("/api/v1/agents?$query") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(id.toString()) }
            jsonPath("$.page") { value(2) }
            jsonPath("$.size") { value(5) }
            jsonPath("$.total") { value(11) }
        }
        mockMvc.get("/api/v1/agents") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
            jsonPath("$.total") { value(0) }
        }
        mockMvc.get("/api/v1/agents?phase=EXPLODED") { with(user) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/agents/$id") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.executionId") { value("uid-1:1") }
            jsonPath("$.generation") { value(1) }
        }
        // `{id}` is constrained to the UUID pattern (so `/watch` never binds to it); anything else has no route
        mockMvc.get("/api/v1/agents/not-a-uuid") { with(user) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET list resolves mine to the caller and createdBy to the given user`() {
        val other = UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f")
        val external = run.copy(id = UUID.randomUUID(), source = RunSource.EXTERNAL, createdBy = null)
        every { service.list(AgentRunFilter(createdBy = TestUsers.ALICE_ID), 0, 20) } returns
            Page(items = listOf(run), page = 0, size = 20, total = 1)
        every { service.list(AgentRunFilter(createdBy = other), 0, 20) } returns
            Page(items = emptyList(), page = 0, size = 20, total = 0)
        every { service.list(AgentRunFilter(), 0, 20) } returns
            Page(items = listOf(run, external), page = 0, size = 20, total = 2)

        mockMvc.get("/api/v1/agents?mine=true") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].createdBy.username") { value(TestUsers.alice.username) }
        }
        mockMvc.get("/api/v1/agents?mine=true&createdBy=${TestUsers.ALICE_ID}") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
        }
        mockMvc.get("/api/v1/agents?createdBy=$other") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
        }
        mockMvc.get("/api/v1/agents?mine=false") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[1].createdBy") { value(null) }
        }
        mockMvc.get("/api/v1/agents?mine=true&createdBy=$other") { with(user) }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("mine=true cannot be combined with createdBy=$other") }
        }
        mockMvc.get("/api/v1/agents?mine=maybe") { with(user) }.andExpect { status { isBadRequest() } }
        verify(exactly = 1) { service.list(AgentRunFilter(createdBy = other), any(), any()) }
    }

    @Test
    fun `POST cancel returns 202 with the cancelled run`() {
        every { service.cancel(id) } returns run.copy(phase = AgentPhase.CANCELLED, reason = "CancelledByUser")

        mockMvc.post("/api/v1/agents/$id/cancel") { with(user) }.andExpect {
            status { isAccepted() }
            jsonPath("$.phase") { value("CANCELLED") }
            jsonPath("$.reason") { value("CancelledByUser") }
        }
        verify(exactly = 1) { service.cancel(id) }
    }

    @Test
    fun `requests without a bearer token are rejected with a 401 problem detail`() {
        mockMvc.get("/api/v1/agents").andExpect {
            status { isUnauthorized() }
            header { exists("WWW-Authenticate") }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
        }
        verify(exactly = 0) { service.list(any(), any(), any()) }
    }

    companion object {
        private const val SHA_LENGTH = 40
    }
}
