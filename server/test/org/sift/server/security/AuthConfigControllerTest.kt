package org.sift.server.security

import com.ninjasquad.springmockk.MockkBean
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.server.config.ServerProperties
import org.sift.server.users.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

@WebMvcTest(
    controllers = [AuthConfigController::class],
    excludeAutoConfiguration = [ExposedAutoConfiguration::class],
    properties = [
        "sift.server.encryption-key=dGVzdA==",
        "sift.server.auth.client-id=sift-spa",
        "sift.server.auth.scopes=openid,email",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.example.org/realms/sift",
        "spring.security.oauth2.resourceserver.jwt.audiences=sift-server",
    ],
)
@Import(SecurityConfiguration::class, TestSecurityConfiguration::class)
@EnableConfigurationProperties(ServerProperties::class)
class AuthConfigControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    /** Required by the security chain's provisioning filter; anonymous requests never reach it. */
    @MockkBean
    private lateinit var users: UserService

    @Test
    fun `exposes issuer client id and scopes anonymously and nothing else`() {
        mockMvc.get("/api/v1/auth/config").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.issuerUri") { value("https://idp.example.org/realms/sift") }
            jsonPath("$.clientId") { value("sift-spa") }
            jsonPath("$.scopes.length()") { value(2) }
            jsonPath("$.scopes[0]") { value("openid") }
            jsonPath("$.scopes[1]") { value("email") }
            jsonPath("$.length()") { value(3) }
            jsonPath("$.audiences") { doesNotExist() }
            jsonPath("$.clientSecret") { doesNotExist() }
        }
    }
}
