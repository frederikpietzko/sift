package org.sift.server.users.web

import com.ninjasquad.springmockk.MockkBean
import io.mockk.verify
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.server.api.ApiExceptionHandler
import org.sift.server.security.SecurityConfiguration
import org.sift.server.security.TestSecurityConfiguration
import org.sift.server.security.TestTokens
import org.sift.server.users.TestUsers
import org.sift.server.users.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.BeforeTest
import kotlin.test.Test

/** `Application` imports [ExposedAutoConfiguration] explicitly; the MVC slice has no data source, so it is excluded. */
@WebMvcTest(controllers = [MeController::class], excludeAutoConfiguration = [ExposedAutoConfiguration::class])
@Import(ApiExceptionHandler::class, SecurityConfiguration::class, TestSecurityConfiguration::class)
class MeControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var users: UserService

    @BeforeTest
    fun stubProvisioning() = TestUsers.stubProvisioning(users)

    @Test
    fun `GET me returns the user provisioned from the bearer token`() {
        mockMvc.get("/api/v1/me") { with(TestTokens.authenticated()) }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.id") { value(TestUsers.ALICE_ID.toString()) }
            jsonPath("$.subject") { value(TestTokens.SUBJECT) }
            jsonPath("$.issuer") { value(TestTokens.ISSUER) }
            jsonPath("$.username") { value(TestTokens.USERNAME) }
            jsonPath("$.email") { value(TestTokens.EMAIL) }
            jsonPath("$.length()") { value(5) }
        }
        verify(exactly = 1) { users.provision(match { it.subject == TestTokens.SUBJECT }) }
    }

    @Test
    fun `a raw bearer token without username claim is provisioned with the subject as username`() {
        mockMvc.get("/api/v1/me") {
            header(HttpHeaders.AUTHORIZATION, TestTokens.authorization(TestTokens.bearer(subject = "sub-9", username = null, email = null)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.subject") { value("sub-9") }
            jsonPath("$.username") { value("sub-9") }
            jsonPath("$.email") { doesNotExist() }
        }
    }

    @Test
    fun `anonymous requests get a 401 and are never provisioned`() {
        mockMvc.get("/api/v1/me").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
        verify(exactly = 0) { users.provision(any()) }
    }
}
