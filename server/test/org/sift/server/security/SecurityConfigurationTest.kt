package org.sift.server.security

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.server.agents.AgentRunController
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.AgentRunService
import org.sift.server.agents.Page
import org.sift.server.api.ApiExceptionHandler
import org.sift.server.config.ServerProperties
import org.sift.server.users.TestUsers
import org.sift.server.users.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The filter chain itself: which routes are open, how failures are rendered and that the decoder is consulted for
 * raw `Authorization` headers. [AgentRunController] and [AuthConfigController] only serve as protected/open targets.
 */
@WebMvcTest(
    controllers = [AgentRunController::class, AuthConfigController::class],
    excludeAutoConfiguration = [ExposedAutoConfiguration::class],
    properties = ["sift.server.encryption-key=dGVzdA==", "sift.server.auth.client-id=sift-web"],
)
@Import(ApiExceptionHandler::class, SecurityConfiguration::class, TestSecurityConfiguration::class)
@EnableConfigurationProperties(ServerProperties::class)
class SecurityConfigurationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: AgentRunService

    @MockkBean
    private lateinit var users: UserService

    @BeforeTest
    fun stubProvisioning() = TestUsers.stubProvisioning(users)

    @Test
    fun `anonymous API requests get a 401 problem detail with a bearer challenge`() {
        mockMvc.get("/api/v1/agents").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.title") { value("Unauthorized") }
            jsonPath("$.detail") { value("Authentication is required to access this resource") }
            jsonPath("$.instance") { value("/api/v1/agents") }
        }
        mockMvc.post("/api/v1/agents") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }
        verify(exactly = 0) { service.list(any(), any(), any()) }
        verify(exactly = 0) { service.create(any(), any()) }
        verify(exactly = 0) { users.provision(any()) }
    }

    @Test
    fun `malformed and rejected bearer tokens get a 401 with an invalid_token challenge`() {
        listOf("not-base64!!", TestTokens.REJECTED).forEach { token ->
            mockMvc.get("/api/v1/agents") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }.andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")) }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(401) }
            }
        }
        verify(exactly = 0) { users.provision(any()) }
    }

    @Test
    fun `a decodable bearer token authenticates the request`() {
        every { service.list(AgentRunFilter(), 0, 20) } returns Page(emptyList(), 0, 20, 0)

        mockMvc.get("/api/v1/agents") {
            header(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/agents") { with(TestTokens.authenticated()) }.andExpect { status { isOk() } }
        verify(exactly = 2) { service.list(AgentRunFilter(), 0, 20) }
        verify(exactly = 2) { users.provision(match { it.subject == TestTokens.SUBJECT }) }
    }

    @Test
    fun `auth config is anonymous but only for GET`() {
        mockMvc.get("/api/v1/auth/config").andExpect {
            status { isOk() }
            jsonPath("$.clientId") { value("sift-web") }
        }
        mockMvc.post("/api/v1/auth/config").andExpect { status { isUnauthorized() } }
        verify(exactly = 0) { users.provision(any()) }
    }

    @Test
    fun `unknown routes still require authentication`() {
        mockMvc.get("/api/v1/nope").andExpect { status { isUnauthorized() } }
    }
}
