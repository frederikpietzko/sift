package org.sift.server.results

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.events.Severity
import org.sift.server.agents.Page
import org.sift.server.api.ApiExceptionHandler
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
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `Application` imports [ExposedAutoConfiguration] explicitly; the MVC slice has no data source, so it is excluded. */
@WebMvcTest(controllers = [ReviewResultController::class], excludeAutoConfiguration = [ExposedAutoConfiguration::class])
@Import(ApiExceptionHandler::class, SecurityConfiguration::class, TestSecurityConfiguration::class)
class ReviewResultControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: ReviewResultService

    @MockkBean
    private lateinit var users: UserService

    private val user = TestTokens.authenticated()

    @BeforeTest
    fun stubProvisioning() = TestUsers.stubProvisioning(users)

    private val id: UUID = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5")
    private val runId: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val sha = "a".repeat(SHA_LENGTH)
    private val result = ReviewResult(
        id = id,
        executionId = "uid-1:1",
        agentRunId = runId,
        repositoryUrl = "https://example.org/alpha.git",
        branch = "feature/x",
        baseBranch = "main",
        commitSha = sha,
        pullRequest = "42",
        summary = "two nits",
        completedAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
        receivedAt = OffsetDateTime.parse("2026-09-07T10:00:05Z"),
    )
    private val findings = listOf(
        ReviewFinding(1L, id, "a.kt", 1, 2, Severity.MAJOR, "style", "rename", "call it x"),
        ReviewFinding(2L, id, "b.kt", null, null, Severity.INFO, null, "nit", null),
    )

    @Test
    fun `GET list passes filters and paging and adds finding counts`() {
        val filter = ReviewResultFilter(repositoryUrl = "https://example.org/alpha.git", commitSha = sha, agentRunId = runId)
        every { service.list(filter, 2, 5) } returns Page(items = listOf(result), page = 2, size = 5, total = 11)
        every { service.findingCounts(listOf(id)) } returns mapOf(id to 2L)
        every { service.list(ReviewResultFilter(), 0, 20) } returns Page(items = emptyList(), page = 0, size = 20, total = 0)
        every { service.findingCounts(emptyList()) } returns emptyMap()

        val query = "repositoryUrl=https://example.org/alpha.git&commitSha=$sha&agentRunId=$runId&page=2&size=5"
        mockMvc.get("/api/v1/results?$query") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(id.toString()) }
            jsonPath("$.items[0].executionId") { value("uid-1:1") }
            jsonPath("$.items[0].agentRunId") { value(runId.toString()) }
            jsonPath("$.items[0].repositoryUrl") { value("https://example.org/alpha.git") }
            jsonPath("$.items[0].commitSha") { value(sha) }
            jsonPath("$.items[0].pullRequest") { value("42") }
            jsonPath("$.items[0].summary") { value("two nits") }
            jsonPath("$.items[0].findingCount") { value(2) }
            jsonPath("$.items[0].completedAt") { exists() }
            jsonPath("$.items[0].receivedAt") { exists() }
            jsonPath("$.items[0].findings") { doesNotExist() }
            jsonPath("$.page") { value(2) }
            jsonPath("$.size") { value(5) }
            jsonPath("$.total") { value(11) }
        }
        mockMvc.get("/api/v1/results") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
            jsonPath("$.total") { value(0) }
        }
        mockMvc.get("/api/v1/results?agentRunId=nope") { with(user) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `GET by id returns the result with its findings`() {
        every { service.get(id) } returns result
        every { service.findings(id) } returns findings

        mockMvc.get("/api/v1/results/$id") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id.toString()) }
            jsonPath("$.branch") { value("feature/x") }
            jsonPath("$.baseBranch") { value("main") }
            jsonPath("$.findingCount") { value(2) }
            jsonPath("$.findings.length()") { value(2) }
            jsonPath("$.findings[0].id") { value(1) }
            jsonPath("$.findings[0].file") { value("a.kt") }
            jsonPath("$.findings[0].startLine") { value(1) }
            jsonPath("$.findings[0].endLine") { value(2) }
            jsonPath("$.findings[0].severity") { value("MAJOR") }
            jsonPath("$.findings[0].category") { value("style") }
            jsonPath("$.findings[0].message") { value("rename") }
            jsonPath("$.findings[0].suggestion") { value("call it x") }
            jsonPath("$.findings[1].startLine") { doesNotExist() }
            jsonPath("$.findings[1].severity") { value("INFO") }
            jsonPath("$.findings[0].resultId") { doesNotExist() }
        }
    }

    @Test
    fun `GET findings passes severity and file filters`() {
        every { service.findings(id, Severity.MAJOR, "a.kt") } returns listOf(findings.first())
        every { service.findings(id, null, null) } returns findings

        mockMvc.get("/api/v1/results/$id/findings?severity=MAJOR&file=a.kt") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].file") { value("a.kt") }
            jsonPath("$[0].severity") { value("MAJOR") }
        }
        mockMvc.get("/api/v1/results/$id/findings") { with(user) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
        mockMvc.get("/api/v1/results/$id/findings?severity=HUGE") { with(user) }.andExpect { status { isBadRequest() } }
        verify(exactly = 1) { service.findings(id, Severity.MAJOR, "a.kt") }
    }

    @Test
    fun `unknown ids map to 404 problem details`() {
        val missing = UUID.randomUUID()
        every { service.get(missing) } throws NotFoundException("Review result $missing not found")
        every { service.findings(missing, null, null) } throws NotFoundException("Review result $missing not found")

        mockMvc.get("/api/v1/results/$missing") { with(user) }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Review result $missing not found") }
        }
        mockMvc.get("/api/v1/results/$missing/findings") { with(user) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Review result $missing not found") }
        }
        mockMvc.get("/api/v1/results/not-a-uuid") { with(user) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `results require a bearer token`() {
        mockMvc.get("/api/v1/results").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
        verify(exactly = 0) { service.list(any(), any(), any()) }
    }

    companion object {
        private const val SHA_LENGTH = 40
    }
}
